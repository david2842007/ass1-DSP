package com.example;

public class utils {

    public static String waitForResult(String queueName) {
        AWS aws = AWS.getInstance();
        String body;
        do {
            body = aws.receiveOneMessage(queueName);
        } while (body == null);
        return body;
    }

    public static String[] waitForJobResult(String queueName) {
        AWS aws = AWS.getInstance();
        String[] body;
        do {
            body = aws.receiveJob(queueName);
        } while (body == null);
        return body;
    }
}
