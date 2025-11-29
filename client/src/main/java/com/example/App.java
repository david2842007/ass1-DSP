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
    final static Path  InputPath = Paths.get(FilesPath + inputFileKey);
    final static Path  OutputPath = Paths.get(FilesPath + outputFileKey);

    //queus
    final static String inputQueueName = "inputQueue";
    final static String outputQueueBaseName = "outputQueue";
    final static String flagsQueueName = "flagsQueue";

    //flags
    final static String terminateFlag = "terminate";




    public static void main(String[] args) {

        setup();
        String ec2Script = buildUserDataScript();
        String managerId = aws.ensureManagerIsRunning(ec2Script);




        String inputFile = aws.uploadFile(inputFileKey, InputPath);
        String outputQueueName = outputQueueBaseName + System.currentTimeMillis();


        aws.createSqsQueue(inputQueueName);
        aws.createSqsQueue(outputQueueName);
        aws.createSqsQueue(flagsQueueName);


        aws.sendJobMessage(inputQueueName, inputFile, outputQueueName);
        String outputLoc = waitForResult(outputQueueName);
        System.out.println("Output location: " + outputLoc);
        /*
        aws.downloadFile(outputLoc, OutputPath);
        if(needTerminate(args)){
            aws.sendMessage(flagsQueueName, terminateFlag);
        }
        /*
 */
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
        String gitUser = System.getenv("GITHUB_USER");

        if (gitUser == null || gitUser.isEmpty()) {
            throw new RuntimeException("GITHUB_USER environment variable not set!");
        }

        return """
        #!/bin/bash
        set -e

        # Provided from Java/Env:
        GITHUB_USER="Frogy123"
        REPO_NAME="ass1-DSP"
        BRANCH_NAME="main"
        MODULE_NAME="manager"
        APP_USER="ec2-user"

        echo "[*] Installing git, Java, Maven..."
        sudo dnf update -y
        sudo dnf install -y java-17-amazon-corretto-headless 
        sudo dnf install -y git
        sudo dnf install -y maven

        cd /home/$APP_USER

        REPO_DIR="/home/${APP_USER}/${REPO_NAME}"

        if [ ! -d "$REPO_DIR" ]; then
          echo "[*] Cloning repository https://github.com/${GITHUB_USER}/${REPO_NAME}.git..."
          sudo -u $APP_USER git clone "https://github.com/david2842007/ass1-DSP"
        else
          echo "[*] Updating repository..."
          cd "$REPO_DIR"
          sudo -u $APP_USER git fetch origin
          sudo -u $APP_USER git checkout "$BRANCH_NAME"
          sudo -u $APP_USER git pull origin "$BRANCH_NAME"
        fi

        cd "$REPO_DIR"

        echo "[*] Building module $MODULE_NAME..."
        sudo -u $APP_USER mvn clean package -pl $MODULE_NAME -am -DskipTests

        echo "[*] Killing old process..."
        pkill -f "$MODULE_NAME.*.jar" || true

        JAR_PATH=$(ls $MODULE_NAME/target/*.jar | head -n 1)

        echo "[*] Running $MODULE_NAME"
        sudo -u $APP_USER nohup java -jar "$JAR_PATH" > /home/$APP_USER/$MODULE_NAME.log 2>&1 &

        echo "[*] Finished EC2 bootstrap!"
        """.formatted(gitUser);  // <-- inject your env variable
    }
    //waits 10sec before trying again.





}
