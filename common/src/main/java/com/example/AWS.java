package com.example;

import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AWS {
    private final S3Client s3;
    private final SqsClient sqs;
    private final Ec2Client ec2;

    public static String ami = "ami-00e95a9222311e8ed";

    public static Region region1 = Region.US_WEST_2;
    public static Region region2 = Region.US_EAST_1;

    private static final AWS instance = new AWS();

    private HashMap<String, String> queueToUrls;

    private AWS() {
        s3 = S3Client.builder().region(region1).build();
        sqs = SqsClient.builder().region(region1).build();
        ec2 = Ec2Client.builder().region(region2).build();
        queueToUrls = new HashMap<>();
    }

    public static AWS getInstance() {
        return instance;
    }

    public String bucketName = "dsp-ass1-321856736937";


    // S3
    public void createBucketIfNotExists(String bucketName) {
        try {
            s3.createBucket(CreateBucketRequest
                    .builder()
                    .bucket(bucketName)
                    .createBucketConfiguration(
                            CreateBucketConfiguration.builder()
                                    .locationConstraint(BucketLocationConstraint.US_WEST_2)
                                    .build())
                    .build());
            s3.waiter().waitUntilBucketExists(HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build());
        } catch (S3Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private String uploadFile(String bucket, String key, Path localPath) {
        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        s3.putObject(putReq, localPath);

        System.out.printf("[INFO] Uploaded %s to s3://%s/%s%n",
                localPath, bucket, key);

        return key;
    }

    public String uploadFile(String key, Path localPath) {
        return uploadFile(bucketName, key, localPath);
    }

    private void downloadFile(String bucketName, String key, Path destination) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3.getObject(getObjectRequest, ResponseTransformer.toFile(destination));
    }
    public void downloadFile(String key, Path destination) {
        downloadFile(bucketName, key, destination);
    }


    // EC2
    public String createEC2(String script, String tagName, int numberOfInstances) {
        Ec2Client ec2 = Ec2Client.builder().region(region2).build();
        RunInstancesRequest runRequest = (RunInstancesRequest) RunInstancesRequest.builder()
                .instanceType(InstanceType.M4_LARGE)
                .imageId(ami)
                .maxCount(numberOfInstances)
                .minCount(1)
                .keyName("vockey")
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build())
                .userData(Base64.getEncoder().encodeToString((script).getBytes()))
                .build();


        RunInstancesResponse response = ec2.runInstances(runRequest);

        String instanceId = response.instances().get(0).instanceId();

        software.amazon.awssdk.services.ec2.model.Tag tag = Tag.builder()
                .key("Name")
                .value(tagName)
                .build();

        CreateTagsRequest tagRequest = (CreateTagsRequest) CreateTagsRequest.builder()
                .resources(instanceId)
                .tags(tag)
                .build();

        try {
            ec2.createTags(tagRequest);
            System.out.printf(
                    "[DEBUG] Successfully started EC2 instance %s based on AMI %s\n",
                    instanceId, ami);

        } catch (Ec2Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(1);
        }
        return instanceId;
    }
    //SQS queues.
    public void createSqsQueue(String queueName) {
        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();
        sqs.createQueue(createQueueRequest);
    }

    public String getQueueUrl(String queueName) {
        String url = queueToUrls.get(queueName);

        if (url == null) {
            try {
                url = sqs.getQueueUrl(
                        GetQueueUrlRequest.builder()
                                .queueName(queueName)
                                .build()
                ).queueUrl();

                // Only cache if success
                queueToUrls.put(queueName, url);

            } catch (QueueDoesNotExistException e) {
                throw new RuntimeException(
                        "Queue does not exist: " + queueName, e
                );
            }
        }

        return url;
    }

    public boolean sendMessage(String queueName, String body) {
        String queueUrl = null;
        try {
            queueUrl = getQueueUrl(queueName);
        }catch (RuntimeException e){
            System.out.println("[ERROR] couldn't send message because - " + e.getMessage());
            return false;
        }

        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build();

        sqs.sendMessage(request);

        System.out.printf("[SQS] Sent message to %s: %s%n", queueName, body);
        return true;
    }

    public boolean sendJobMessage(String queueName, String fileKey, String responseQueue){
        String queueUrl = null;
        try {
            queueUrl = getQueueUrl(queueName);
        }catch (RuntimeException e){
            System.out.println("[ERROR] couldn't send message because - " + e.getMessage());
            return false;
        }

        Map<String, MessageAttributeValue> attributes = new HashMap<>();

        attributes.put(
                "responseQueue",
                MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(responseQueue)
                        .build()
        );

        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(fileKey) // just the file key
                .messageAttributes(attributes)
                .build();

        sqs.sendMessage(request);
        return true;
    }

    public String receiveOneMessage(String queueName) {
        String queueUrl = null;
        try {
            queueUrl = getQueueUrl(queueName);
        }catch (RuntimeException e){
            System.out.println("[ERROR] couldn't send message because - " + e.getMessage());
            return null;
        }

        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(10)   // long polling
                .build();

        List<Message> messages = sqs.receiveMessage(request).messages();

        if (messages.isEmpty()) {
            return null;
        }

        Message msg = messages.get(0);

        // Delete message after processing
        DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(msg.receiptHandle())
                .build();

        sqs.deleteMessage(deleteRequest);

        System.out.printf("[SQS] Received message from %s: %s%n", queueName, msg.body());

        return msg.body();
    }




    public Instance findManager(Ec2Client ec2) {

        DescribeInstancesRequest req = DescribeInstancesRequest.builder()
                .filters(
                        Filter.builder()
                                .name("tag:Name")
                                .values("Manager")
                                .build(),
                        Filter.builder()
                                .name("instance-state-name")
                                .values("pending", "running", "stopped", "stopping")
                                .build()
                )
                .build();

        DescribeInstancesResponse res = ec2.describeInstances(req);

        if (res.reservations().isEmpty()) {
            return null; // No Manager exists
        }

        return res.reservations().get(0).instances().get(0);
    }

    public String ensureManagerIsRunning(String managerUserDataScript) {

        Ec2Client ec2 = Ec2Client.builder().region(region2).build();

        // 1. Check if manager exists
        Instance manager = findManager(ec2);

        if (manager == null) {
            // No manager → create new one
            System.out.println("[INFO] No Manager found. Creating a new one...");
            return createEC2(managerUserDataScript, "Manager", 1);
        }

        String instanceId = manager.instanceId();
        String state = manager.state().nameAsString();

        // 2. If found and running → return it
        if (state.equals("running") || state.equals("pending")) {
            System.out.println("[INFO] Manager already running: " + instanceId);
            return instanceId;
        }

        // 3. If found but stopped → start it
        if (state.equals("stopped")) {
            System.out.println("[INFO] Manager found but stopped. Starting: " + instanceId);

            StartInstancesRequest startReq = StartInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            ec2.startInstances(startReq);
            return instanceId;
        }

        // 4. Otherwise
        System.out.println("[WARN] Manager state = " + state + ". Using it anyway...");
        return instanceId;
    }
}
