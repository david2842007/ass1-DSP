package com.example;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.AWS.*;
import static com.example.utils.*;



import java.util.ArrayList;
import java.util.List;

class WorkersManager {

    private final AWS aws;
    private final int messagesPerWorker;  // n from the spec
    private final int maxWorkers = 19;

    // All worker instance IDs we created from this manager
    private final List<String> workerInstanceIds = new ArrayList<>();

    // The user-data script that will start WorkerMain on each worker EC2
    private final String workerUserDataScript;

    public WorkersManager(AWS aws, int messagesPerWorker, String workerUserDataScript) {
        this.aws = aws;
        this.messagesPerWorker = messagesPerWorker;
        this.workerUserDataScript = workerUserDataScript;
    }

    /**
     * Called when a NEW task arrives.
     * jobMessages = number of URLs / lines in this input file.
     *
     * We compute how many workers this job "needs", and if we have fewer
     * than that currently, we create more (up to maxWorkers).
     */
    public synchronized void ensureWorkersForNewJob(int jobMessages) {
        if (jobMessages <= 0) {
            return;
        }

        int requiredForThisJob = (int) Math.ceil(jobMessages / (double) messagesPerWorker);

        int currentWorkers = workerInstanceIds.size();
        int desiredWorkers = Math.min(maxWorkers,
                Math.max(currentWorkers, requiredForThisJob));

        if (desiredWorkers <= currentWorkers) {
            System.out.printf("[DEBUG] WorkersManager: already have %d workers, required %d → no new workers\n",
                    currentWorkers, requiredForThisJob);
            return;
        }

        int toCreate = desiredWorkers - currentWorkers;
        System.out.printf("[DEBUG] WorkersManager: creating %d new worker(s). current=%d, requiredForJob=%d\n",
                toCreate, currentWorkers, requiredForThisJob);

        List<String> newIds = aws.createWorkerInstances(workerUserDataScript, toCreate);
        workerInstanceIds.addAll(newIds);

        System.out.printf("[DEBUG] WorkersManager: total workers now = %d\n", workerInstanceIds.size());
    }

    /**
     * Terminate all worker instances we’ve created and tracked.
     * Call this once you know all tasks are done (termination message flow).
     */
    public synchronized void terminateAll() {
        if (workerInstanceIds.isEmpty()) {
            System.out.println("[DEBUG] WorkersManager: no workers to terminate.");
            return;
        }

        System.out.println("[DEBUG] WorkersManager: terminating all workers: " + workerInstanceIds);
        aws.terminateInstances(workerInstanceIds);
        workerInstanceIds.clear();
    }

    public synchronized int getCurrentWorkerCount() {
        return workerInstanceIds.size();
    }
}


class TaskState {
    final String outputQueueName;
    BufferedReader inputReader = null;
    BufferedWriter outputWriter = null;


    final Path inputPath;
    final Path outputPath;

    final AtomicInteger linesSent = new AtomicInteger(0);
    final AtomicInteger linesReceived = new AtomicInteger(0);

    volatile boolean sendingFinished = false;  // true when reader hit EOF
    int totalLines; // optional, if you know it in advance

    TaskState(String outputQueueName, Path _inputPath, Path _outputPath) {
        this.outputQueueName = outputQueueName;
        this.inputPath = _inputPath;
        this.outputPath = _outputPath;

        try {
            this.inputReader = Files.newBufferedReader(_inputPath);
            this.outputWriter = Files.newBufferedWriter(_outputPath);

        }catch(IOException e){
            System.err.println("[ERROR] cant create a task because " +e.getMessage());
        }

    }

    String readNextLine() {
        if (sendingFinished || inputReader == null) return null;

        try {
            String line = inputReader.readLine();
            if (line == null || line.equals("")) {
                sendingFinished = true;
                inputReader.close();
            }
            return line;
        } catch (IOException e) {
            System.err.println("[ERROR] reading input for " + outputQueueName + ": " + e.getMessage());
            sendingFinished = true;
            return null;
        }
    }

    /** Called only from collector thread */
    void writeResultLine(String processedLine) {
        if (outputWriter == null) return;

        try {
            outputWriter.write(processedLine);
            outputWriter.newLine();
            linesReceived.incrementAndGet();
        } catch (IOException e) {
            System.err.println("[ERROR] writing output for " + outputQueueName + ": " + e.getMessage());
        }
    }

    /** Called when you detect task is complete */
    void finishAndClose() {
        try {
            if (outputWriter != null) {
                outputWriter.flush();
                outputWriter.close();
            }
        } catch (IOException e) {
            System.err.println("[ERROR] closing writer for " + outputQueueName + ": " + e.getMessage());
        }
    }

    public String getOutputQueueName() {
        return outputQueueName;
    }


}

public class ManagerMain {

    //final static String localPath = "/home/ec2-user/";
    final static String localPath = "C:\\git\\University\\distribuited_system\\ass1-DSP\\manager\\files";

    final static AWS aws = AWS.getInstance();

    //queues
    final static String inputQueueName = "inputQueue";
    final static String outputQueueBaseName = "outputQueue";
    final static String flagsQueueName = "flagsQueue";
    final static String workersInputQueueName = "ass1-worker-queue";
    final static String workersOutputQueueName = "ass1-manager-queue";



    static private final Map<String, TaskState> tasks = new ConcurrentHashMap<>(); //localPcs output queues name to

    private static volatile boolean terminate = false;


    // how many messages per one worker EC2
    final static int MESSAGES_PER_WORKER = 50;

    // user-data script that runs WorkerMain on each worker EC2
    // (this is just an example, adjust to your jar/classpath)
    static final String WORKER_USER_DATA_SCRIPT =
            "#!/bin/bash\n" +
                    "cd /home/ec2-user\n" +
                    "java -jar WorkerMain.jar\n";

    static final WorkersManager workersManager =
            new WorkersManager(aws, MESSAGES_PER_WORKER, WORKER_USER_DATA_SCRIPT);


    public static void main(String[] args){
        terminate = false;
        aws.createSqsQueue(workersInputQueueName);
        aws.createSqsQueue(workersOutputQueueName);

        Thread pollerThread = new Thread(() -> {
            while(!terminate){
                String[] message = aws.receiveJobAsync(inputQueueName, 1);//waits for the input file task.
                String flag = aws.receiveOneMessage(flagsQueueName);
                if(flag != null && flag.equals("terminate")){
                    terminate = true;
                }
                if(message != null){
                    String inputKey = message[0];
                    String outputQueueName = message[1];
                    System.out.println("[DEBUG] File name:" + inputKey + ": " + outputQueueName);
                    long timeKey = System.currentTimeMillis();

                    Path[] paths = createUniqueInputOutputPath();
                    Path inputPath = paths[0];
                    Path outputPath = paths[1];

                    aws.downloadFile(inputKey, inputPath);
                    int jobMessages = countLines(inputPath);

                    TaskState task = new TaskState(outputQueueName, inputPath, outputPath);
                    tasks.put(outputQueueName, task);

                    workersManager.ensureWorkersForNewJob(jobMessages);

                    System.out.println("Created task for queue " + outputQueueName +
                            " with " + jobMessages + " lines.");
                }
            }
        }, "pollerThread");


        // 2) Dispatcher: round-robin send lines to workersInputQueue
        Thread dispatcherThread = new Thread(() -> {
            while (!terminate || !tasks.isEmpty()) {
                boolean anyActive = false;

                for (TaskState task : tasks.values()) {
                    if (task.sendingFinished || task.inputReader == null) {
                        continue;
                    }
                    anyActive = true;

                    String line = task.readNextLine();
                    if(line != null && !line.equals("")){
                        aws.sendJobMessage(workersInputQueueName, line, task.outputQueueName);
                        task.linesSent.incrementAndGet();
                    }
                }
                // If no active tasks, sleep a bit to avoid burning CPU
                if (!anyActive) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "DispatcherThread");

        Thread collectorThread = new Thread(() -> {

            while(!terminate || !tasks.isEmpty()){
                String[] message = aws.receiveJobAsync(workersOutputQueueName, 10);
                if(message != null){
                    String lineToWrite = message[0];
                    String outQueue = message[1];

                    TaskState task = tasks.get(outQueue);
                    if (task == null) {
                        System.err.println("[WARN] got result for unknown task " + outQueue);
                        continue;
                    }
                    if(!isErrorLine(lineToWrite)){
                        System.out.println("writing: " + lineToWrite +" | to outputfile " + task.outputQueueName);
                        task.writeResultLine(lineToWrite);
                    }else{
                       task.linesReceived.incrementAndGet();
                    }
                    System.out.println("[DEBUG] Recived: "  + task.linesReceived.get()+ " lines");



                    if(task.sendingFinished && task.linesSent.get() == task.linesReceived.get()){
                        task.finishAndClose();
                        String fileKey = "Output" + System.currentTimeMillis() + ".txt";
                        aws.uploadFileAndNotifyPc(fileKey, task.outputPath, task.outputQueueName);
                        tasks.remove(outQueue);
                        System.out.println("Finished writing: " + fileKey + " to outputfile " + task.outputQueueName);
                    }
                }
            }
            //workersManager.terminateAll();


        }, "collectorThread");


        pollerThread.start();
        dispatcherThread.start();
        collectorThread.start();

        // block main so it doesn't just exit
        try {
            pollerThread.join();
            dispatcherThread.join();
            collectorThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }






    }

    public static boolean isErrorLine(String s) {
        return s == null || s.startsWith("ERROR");
    }


    public static Path[] createUniqueInputOutputPath(){
        long timeKey = System.currentTimeMillis();
        Path inputPath  = Paths.get(localPath + "/input" + timeKey  + ".txt");
        Path outputPath  = Paths.get(localPath + "/output" + timeKey  + ".txt");
        return new Path[]{inputPath, outputPath};
    }

    private static int countLines(Path path) {
        try (BufferedReader r = Files.newBufferedReader(path)) {
            int count = 0;
            while (r.readLine() != null) {
                count++;
            }
            return count;
        } catch (IOException e) {
            System.err.println("[ERROR] counting lines in " + path + ": " + e.getMessage());
            return 0;
        }
    }

}
