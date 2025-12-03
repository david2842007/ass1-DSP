README
======

Course: Distributed Systems - Assignment 1 (AWS)
------------------------------------------------

Authors
-------
- Name: Eitan Navon| ID: 330990375
- Name: David Paster | ID: 216705665

(Replace the values above before submitting.)

1. How to Run the Project
-------------------------

The public entry-point of the system is the **local application JAR**, built from `App.java` / `Main.java`.

### 1.1 Prerequisites

On the **local machine** (your laptop / lab PC):

- Java 17 (or compatible JDK) installed.
- AWS credentials configured *without* hard‑coding keys in the code:
  - Either in `~/.aws/credentials`
  - Or via environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, etc.)
- Maven/Gradle (only needed to build the JAR; not required at runtime).
- An input file in the required format, for example:

  POS\thttps://example.com/file1.txt
  CONSTITUENCY\thttps://example.com/file2.txt
  DEPENDENCY\thttps://example.com/file3.txt

Each line contains:
- The **analysis type** (`POS`, `CONSTITUENCY`, `DEPENDENCY`)
- A TAB (`\t`)
- A URL of a text file.

### 1.2 Running the Local Application (App.jar)

The JAR expects the following arguments:

    java -jar App.jar <input-file> <output-file-name> <n>

Where:

- `<input-file>` — path to the local input file.
- `<output-file-name>` — logical name of the final merged output (used for S3 key and local result).
- `<n>` — fan‑out parameter: the number of input lines per Worker (or the “granularity” of tasks the Manager uses when creating workers).

Example:

    java -jar App.jar input.txt summary.html 5

High‑level flow when you run this command:

1. The local app uploads `input.txt` to S3.
2. It creates the required SQS queues (input, output, flags, worker queue).
3. It launches a **Manager** EC2 instance with a user‑data script that starts `manager.jar`.
4. It waits (polling a dedicated SQS queue) for a “done” notification from the Manager.
5. Once done, it downloads the final result file from S3 (e.g., `summary.html`) and stores it locally.

### 1.3 What Files Are Included

Source files:

- `App.java` – Local application orchestration and high‑level control.
- `Main.java` – Main entry‑point for the local app (parses arguments, calls `App`).
- `ManagerMain.java` – Code that runs on the Manager EC2 instance.
- `AWS.java` – Utility class that wraps all AWS operations (S3, SQS, EC2).
- `utils.java` – Generic helpers (string parsing, sleeping, validation, formatting).
- `README` – This file.

Built artifacts:

- `App.jar` – JAR for the local application.
- `manager.jar` – JAR that runs on the Manager EC2 instance.
- `worker.jar` – JAR that runs on Worker EC2 instances. (If separated.)

2. High-Level System Architecture
---------------------------------

The system is a classic **Manager / Worker** architecture over AWS:

- **Local Application (Client):**
  - Runs on the user’s machine.
  - Uploads the input file to S3.
  - Creates SQS queues (manager input, worker input, manager output/flags).
  - Launches the Manager EC2 instance.
  - Waits for completion and downloads the final result.

- **Manager (EC2, manager.jar):**
  - Downloads the input file from S3.
  - Splits tasks according to `n`.
  - Ensures the correct SQS queues exist.
  - Launches Worker EC2 instances as needed.
  - Sends tasks to the Worker queue.
  - Collects processed results from a dedicated response/output queue.
  - Keeps internal counters of how many tasks are done.
  - Once all tasks for all clients are complete, generates a final merged output file and uploads it to S3.
  - Sends a completion message to the local app.
  - Terminates all Workers and finally terminates itself.

- **Workers (EC2, worker.jar):**
  - Poll the Worker SQS queue for jobs.
  - Each job is essentially: `<ANALYSIS_TYPE>\t<URL>`.
  - The Worker downloads the given URL’s text file.
  - Performs the required NLP task (POS, Constituency, or Dependency analysis).
  - Sends back a result message to the Manager’s output queue.
  - Continues until told to terminate (via special flag messages) or queue empty at the end of processing.

- **AWS Services Used:**
  - **S3** – Persistent storage for:
    - Input files.
    - Intermediate or final output files.
  - **SQS** – Decoupled message queues for:
    - Local → Manager communication.
    - Manager → Worker tasks.
    - Worker → Manager results.
    - Termination/flags.
  - **EC2** – Compute nodes:
    - One Manager instance at a time.
    - Many Worker instances, scaled by input size and `n`.

3. Instance Type, AMI, Runtime, and n
-------------------------------------

(Replace the placeholders below with your actual values after running the system.)

- **Manager instance AMI:** `ami-05eeee1aeb15ecde0`
- **Manager instance type:** `<e.g., t2.micro / t3.small>`  

- **Worker instance AMI:** `ami-05eeee1aeb15ecde0`
- **Worker instance type:** `t3.micro`

- **n used in final run:** `<YOUR N HERE>`  
  - Example: `n = 5` (each Worker was responsible for 5 lines from the input file).

- **Total time to finish processing the official input file(s):**  
  - `<e.g., 2 minutes 37 seconds>`

To measure runtime, we started a timer in the local application just before uploading the input file and stopped it right after the final output was downloaded from S3.

4. Detailed Program Flow
------------------------

### 4.1 Local Application (App/Main)

1. Parse command‑line arguments.
2. Upload the input file to S3 (via `AWS.uploadFile`).
3. Create SQS queues (manager queue, worker queue, flags queue, client‑response queue).
4. Start the Manager EC2 instance with user‑data that:
   - Installs Java
   - Downloads `manager.jar` from S3
   - Starts the Manager.
5. Wait for result:
   - Poll the relevant SQS queue for a “DONE” or “RESULT” message.
6. When result message arrives:
   - Download the final result file from S3.
   - Save it locally as `<output-file-name>`.
7. Optionally, clean up queues / buckets (if not required for debugging).

### 4.2 Manager (ManagerMain)

Internally, the Manager:

1. Downloads the input file from S3 to a local path.
2. Reads each line and turns it into a logical job.
3. Groups jobs according to `n` and creates logical “batches” for Workers.
4. Ensures Worker queue exists.
5. Launches the required number of Worker EC2 instances (via `AWS.createWorkerInstance`).
6. For each job line:
   - Sends a message to the Worker queue with all necessary information (analysis type + URL + IDs for correlation).
7. Listens on the results SQS queue:
   - Each result message includes metadata so the Manager can associate it with the correct input request.
   - Stores partial results in-memory and/or on disk.
8. Uses counters (`AtomicInteger`, concurrent maps) to track how many tasks are expected and how many were completed.
9. Once all tasks are completed for a client:
   - Merges / formats the results into a final output file.
   - Uploads the final output file to S3.
   - Sends a completion message to the local application.
10. When there are no more active clients and all work is done:
    - Sends termination messages to all Workers.
    - Waits for Workers to stop pulling jobs.
    - Calls AWS EC2 APIs to terminate all Worker instances.
    - Finally, the Manager terminates itself (using EC2 metadata to find its own instance ID and calling `TerminateInstances` via the AWS SDK).

### 4.3 Workers

1. Start and connect to SQS.
2. In a loop:
   - Call `receiveMessage` on the Worker queue.
   - If the message is a **terminate** command → exit.
   - Otherwise, parse the job description.
3. For each job:
   - Download the given text (from the URL).
   - Run the requested analysis (POS, Constituency, or Dependency parsing).
   - Send the result to the Manager output queue.
4. Continue until no more jobs and a terminate order is received.

5. Security Considerations
--------------------------

We explicitly avoided sending credentials in plain text and did some basic threat modeling.

### 5.1 Credentials and Authentication

- **No AWS credentials are hard‑coded** in the code or stored in the JAR.
- On the local machine, we rely on the standard AWS credentials chain:
  - `~/.aws/credentials`
  - Environment variables
  - Or lab‑provided configuration.
- On EC2 (Manager and Workers), we rely on **IAM Instance Profiles**:
  - Each instance is launched with an IAM role that grants:
    - Read/write access to the specific S3 bucket used.
    - Access to the specific SQS queues.
    - EC2 operations only where needed (e.g., the Manager can launch Workers and terminate instances).
  - No access keys are stored on disk inside the instances.

### 5.2 Network & Data in Transit

- All communication with S3 and SQS is over HTTPS (handled by the AWS SDK).
- We do not implement any custom encryption, but we rely on AWS TLS‑secured endpoints.

### 5.3 Data at Rest

- Input and output files are stored in S3.
- The bucket can be restricted to our own AWS account (bucket policy / IAM).
- No sensitive user credentials or secrets are logged in output files.

### 5.4 Principle of Least Privilege

- The IAM roles are configured to allow only:
  - S3 access to the specific bucket(s) we use.
  - SQS access to queues with our specific names.
  - EC2 operations used for Worker management.
- The local machine has only the credentials needed to start the Manager and interact with SQS/S3.

6. Scalability Considerations
-----------------------------

We tried to make the system as scalable as reasonably possible within the constraints of the assignment.

### 6.1 Horizontal Scaling via Workers

- Work is split into many **independent tasks** (each line in the input file).
- Tasks are placed in an SQS queue, which scales horizontally.
- We can increase concurrency simply by:
  - Increasing the number of Worker instances the Manager launches.
  - Reducing `n` so each Worker handles fewer lines and we have more Workers.
- SQS can handle very high throughputs (much more than 1M or 2M messages) if configured properly.
- For “1 million clients”, the system can conceptually:
  - Have each client send jobs to the same SQS infrastructure.
  - Have a pool of Managers or one Manager that handles multiple clients (depending on design).

### 6.2 Manager as a Potential Bottleneck

- The Manager can become a bottleneck if:
  - It processes all results in a single thread.
  - It writes large output files synchronously.
- Our design tries to keep the Manager mostly I/O bound:
  - SQS polling is done in loops.
  - Result aggregation is O(number_of_tasks) and relatively lightweight string processing.
- If needed, we can extend the Manager to:
  - Use multiple threads to process results.
  - Or even sharded Managers (not required in this assignment).

### 6.3 Avoiding Centralized Waiting

- Workers do not wait on each other; they only communicate via SQS.
- The Manager does not block on individual Workers; it only waits on messages arriving in the results queue.

In its current form, the system should scale to a large number of tasks as long as AWS limits for SQS, S3, and EC2 are respected.

7. Persistence and Fault Tolerance
----------------------------------

We explicitly thought about what happens when parts of the system fail.

### 7.1 Persistence of Messages

- SQS messages are durable:
  - If a Worker crashes after receiving a message but before deleting it, the message:
    - Becomes visible again after the **visibility timeout**.
    - Another Worker can then re‑process it.
- This naturally handles:
  - Worker instance termination.
  - Temporary network issues.
  - Stalled Workers.

### 7.2 S3 for Persistent Files

- Input files and final output files reside in S3.
- If the Manager fails midway:
  - The input file is still in S3.
  - The local app can restart a new Manager run with the same or modified input.

### 7.3 Manager Failures

- If the Manager EC2 instance dies:
  - The SQS input / output queues still persist.
  - The local application can detect a timeout and decide to:
    - Relaunch a Manager.
    - Or notify the user of failure.
- Within the Manager, we maintain counters and maps that can, in a more advanced version, be rebuilt from SQS if needed (not fully implemented here).

### 7.4 Broken Communications

- All AWS SDK calls are guarded by retry logic via the SDK itself (`SdkException` handling).
- In case of temporary network issues:
  - Uploads to S3 and SQS sends will be retried.
- If a URL for a job is broken or unreachable:
  - The Worker can send back an explicit failure result.
  - The Manager can log the error and still progress on other tasks.

8. Threading Model
------------------

We considered where threads make sense and where they do not.

- The **local application** is essentially single‑threaded:
  - It uploads the input file, starts the Manager, then waits for completion.
- The **Manager**:
  - Can use a background thread for result aggregation while the main thread dispatches tasks, or use a single main loop. (Implementation details are kept simple to avoid race conditions.)
  - Uses concurrency primitives such as `AtomicInteger` and concurrent maps to track the number of completed tasks.
- The **Workers**:
  - Run a simple loop: poll SQS → process message → send result.
  - They do not spawn extra threads; instead, we scale by launching more Worker instances.

**When threads are good in our design:**

- To overlap I/O (S3, SQS) and CPU work.
- To avoid blocking the Manager while waiting for results.

**When threads are bad / avoided:**

- We avoid heavy multithreading inside Workers and Manager to keep the logic simple and robust.
- Instead of many threads in a single process, we use **many EC2 instances**, which is the more natural form of parallelism for a distributed system.

9. Multiple Clients and Termination
-----------------------------------

### 9.1 Multiple Clients

- The system can handle multiple input files sequentially or in parallel:
  - Each input file can get its own logical “job id”.
  - Manager tracks jobs by this id and counts results separately.
- We tested the system by running multiple clients (multiple local runs) and ensured:
  - Each client’s result is independent.
  - All temporary resources eventually get cleaned.

### 9.2 Termination Process

We carefully managed the termination of all components:

1. **Workers**:
   - Manager sends special termination messages in the Worker queue once all work is done.
   - Workers, upon reading a termination message, exit their loop and shut down.
2. **Manager**:
   - After sending termination messages and waiting a short grace period:
     - Calls EC2 to terminate all Worker instances.
   - Finally, the Manager calls `TerminateInstances` on its **own instance ID** (retrieved from the instance metadata service).
3. **Local Application**:
   - Exits after:
     - Downloading the final output file.
     - Printing information about runtime and summary.

We also ensured that:

- SQS queues are not left with unread messages.
- There is no infinite waiting on one side of the system for another.

10. Division of Responsibilities (Manager vs. Workers)
------------------------------------------------------

We explicitly separated responsibilities:

**Manager responsibilities:**

- High‑level orchestration.
- Splitting input into tasks.
- Creating and destroying Workers.
- Aggregating results.
- Managing queues and S3 keys.

**Worker responsibilities:**

- Pure computation:
  - Given a single job (type + URL), do the analysis and return a result.
- No knowledge of:
  - How many other Workers exist.
  - How many clients are active.
  - How the final output is assembled.

This separation ensures that:

- The Manager is not overloaded with low‑level computation.
- The Workers are not responsible for orchestration and system‑wide decisions.

11. Are We Truly Distributed?
-----------------------------

We reflected on what “distributed” means in the context of this assignment:

- **No component waits directly on another specific node**:
  - Communication is always via SQS queues, not via direct TCP between nodes.
- **Workers are stateless**:
  - They do not store global state. Any state is either local to the job or persistent in S3/SQS.
- **The Manager focuses on coordination**, not execution of each job.
- **Horizontal scalability**:
  - To handle more load, we add more Worker instances instead of making a single machine stronger.

In other words, the system is not just “multithreaded” — it is **truly distributed** across multiple EC2 instances, decoupled via AWS managed services (SQS, S3).

12. Pen-and-Paper Run (How We Understand the System)
----------------------------------------------------

We also did a “mental simulation” (pen‑and‑paper run) of the system:

1. Local app reads `input.txt` with 3 lines → uploads to S3.
2. Local app creates queues and launches the Manager.
3. Manager downloads `input.txt`, sees 3 jobs, decides `n = 2`:
   - Launches 2 Workers.
   - Sends first 2 lines to Worker queue (worker group 1).
   - Sends remaining line as another task.
4. Worker 1 picks up job 1 → processes → sends result 1.
5. Worker 2 picks up job 2 → processes → sends result 2.
6. One Worker picks up job 3 → processes → sends result 3.
7. Manager sees all 3 results arrived:
   - Aggregates them in the correct order.
   - Writes `output.html` or similar.
   - Uploads to S3.
   - Sends “DONE + output key” message to local app.
8. Local app downloads the final output and prints “finished”.
9. Manager sends TERMINATE messages to Workers and then terminates them and itself.

This helped us validate that:
- No component busy‑waits unnecessarily.
- All communications go through SQS.
- No data is lost if a Worker fails mid‑job (thanks to SQS visibility).

