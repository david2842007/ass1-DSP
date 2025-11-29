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
}
