package com.example;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.example.AWS.*;
import static com.example.utils.*;





public class ManagerMain {

    final static String localPath = "/home/ec2-user/";
    final static Path inputPath = Paths.get("/home/ec2-user/" + "input.txt");
    final static AWS aws = AWS.getInstance();

    //queues
    final static String inputQueueName = "inputQueue";
    final static String outputQueueName = "outputQueue";
    final static String flagsQueueName = "flagsQueue";
    final static String workersQueueName = "workersQueue";

    public static void main(String[] args){
        boolean terminate = false;
        while(!terminate){

            String message = waitForResult(inputQueueName);//waits for the input file task.
            //aws.downloadFile(inputFile, inputPath);
            //aws.createSqsQueue(workersQueueName);
            aws.sendMessage(outputQueueName, message);

        }





    }



}
