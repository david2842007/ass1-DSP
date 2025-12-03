package com.example;


import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.example.AWS.*;

import static com.example.utils.waitForResult;

public class App {



    final static AWS aws = AWS.getInstance();
    final static String inputFileKey = "/input.txt";
    final static String outputFileKey = "/output.txt";

    final static String rootPath = System.getProperty("user.dir");
    final static String FilesPath = rootPath + "/client/Files";





    //queus
    final static String inputQueueName = "inputQueue";
    final static String outputQueueBaseName = "outputQueue";
    final static String flagsQueueName = "flagsQueue";
    final static String numebrQueueName = "numberQueue";

    //flags
    final static String terminateFlag = "terminate";




    public static void main(String[] args) {

        setup();

        final  Path  InputPath = Paths.get(args[0]);
        final  Path  OutputPath = Paths.get(args[1]);
        final int n = Integer.parseInt(args[2]);



        String ec2Script = buildManagerScript();
        //String managerId = aws.ensureManagerIsRunning(ec2Script);


        String inputFile = aws.uploadFile("input" + System.currentTimeMillis() + ".txt" , InputPath);
        String outputQueueName = outputQueueBaseName + System.currentTimeMillis();


        aws.createSqsQueue(inputQueueName);
        aws.createSqsQueue(outputQueueName);
        aws.createSqsQueue(flagsQueueName);
        aws.createSqsQueue(numebrQueueName);

        if(n > 0){
            aws.sendMessage(numebrQueueName, Integer.toString(n));
        }

        if(needTerminate(args)){
            aws.sendMessage(flagsQueueName, "terminate");
        }





        aws.sendJobMessage(inputQueueName, inputFile, outputQueueName);
        String outputLoc = waitForResult(outputQueueName);
        System.out.println("Output location: " + outputLoc);

        aws.downloadFile(outputLoc, OutputPath);
        aws.deleteQueue(outputQueueName);



    }

    private static boolean needTerminate(String[] args) {
        boolean terminate = false;

        for (String arg : args) {
            if (arg.equals("--terminate")) {
                terminate = true;
            }
        }
        return terminate;
    }


    private static void setup() {
        System.out.println("[DEBUG] Create bucket if not exist.");
        aws.createBucketIfNotExists(aws.bucketName);
    }

    private static void createEC2() {
        String ec2Script = "#!/bin/bash\n" +
                "echo Hello World\n";
        String managerInstanceID = aws.createEC2(ec2Script, "thisIsJustAString", 1);
    }

    private static File getInputFile() {
        System.out.println("[DEBUG] Get input file.");
        File file = new File("input.txt");
        return file;
    }

    static String buildUserDataScript() {
        return ""; // <-- inject your env variable
    }
    //waits 10sec before trying again.

    private static String buildManagerScript() {
        return """
            #!/bin/bash

            # Create manager directory
            mkdir -p /opt/manager
            cd /opt/manager

            # Download manager.jar from S3
            aws s3 cp s3://ass1-packages/manager.jar manager.jar

            # Run the manager in background
            nohup java -jar manager.jar >> manager.log 2>&1 &
            """;
    }





}
