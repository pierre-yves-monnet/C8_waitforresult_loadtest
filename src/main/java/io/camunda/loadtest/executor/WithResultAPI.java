package io.camunda.loadtest.executor;

import io.camunda.tasklist.CamundaTaskListClient;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import io.camunda.zeebe.client.api.response.PublishMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.camunda.tasklist.dto.Task;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class WithResultAPI {
  Logger logger = LoggerFactory.getLogger(WithResultAPI.class.getName());

  private final static String PROCESS_VARIABLE_SNITCH = "SNITCH";

  private final boolean doubleCheck;
  Random random = new Random();

  private final ZeebeClient zeebeClient;

  private final CamundaTaskListClient taskClient;

  private final boolean useTaskAPI;


  DynamicWorker workerImplementation;

  public enum WorkerImplementation { DYNAMIC, HOST}
  public WithResultAPI(ZeebeClient zeebeClient,
                       CamundaTaskListClient taskClient,
                       boolean doubleCheck,
                       boolean useTaskAPI,
                       WorkerImplementation workerImplementation) {
    this.zeebeClient = zeebeClient;
    this.taskClient = taskClient;
    this.doubleCheck = doubleCheck;
    this.useTaskAPI = useTaskAPI;
    this.workerImplementation = new DynamicWorker(zeebeClient);
  }


  /**
   * executeTaskWithResult
   *
   * @param userTask            user task to execute
   * @param assignUser          the user wasn't assign to the user task, so do it
   * @param userName            userName to execute the user task
   * @param variables           Variables to update the task at completion
   * @param timeoutDurationInMs maximum duration time, after the ExceptionWithResult.timeOut is true
   * @return the result variable
   * @throws Exception for any error
   */
  public ExecuteWithResult executeTaskWithResult(Task userTask,
                                                 boolean assignUser,
                                                 String userName,
                                                 Map<String, Object> variables,
                                                 String prefixTopicWorker,
                                                 long timeoutDurationInMs) throws Exception {
    // We need to create a unique ID
    Long beginTime = System.currentTimeMillis();
    String jobKey = userTask.getId();

    logger.debug("ExecuteTaskWithResult[{}]", jobKey);
    int snitchValue = random.nextInt(10000);

    DynamicWorker.LockObjectTransporter lockObjectTransporter= workerImplementation.openTransaction( "ExecuteTask", prefixTopicWorker, jobKey );


    // Now, create a worker just for this jobKey


    Map<String, Object> userVariables = new HashMap<>();
    userVariables.put("jobKey", jobKey);
    userVariables.putAll(variables);
    if (doubleCheck)
      userVariables.put(PROCESS_VARIABLE_SNITCH, snitchValue);
    ExecuteWithResult executeWithResult = new ExecuteWithResult();

    // save the variable jobId
    if (useTaskAPI) {
      try {
        if (assignUser)
          taskClient.claim(userTask.getId(), userName);
        taskClient.completeTask(userTask.getId(), userVariables);
      } catch (Exception e) {
        logger.error("Can't complete Task [{}] : {}", userTask.getId(), e.getMessage());
        executeWithResult.taskNotFound = true;
        return executeWithResult;
      }
    } else {
      try {
        if (assignUser)
          zeebeClient.newUserTaskAssignCommand(Long.parseLong(userTask.getId())).assignee("demo").send().join();
        zeebeClient.newUserTaskCompleteCommand(Long.parseLong(userTask.getId())).variables(userVariables).send().join();
      } catch (Exception e) {
        logger.error("Can't complete Task [{}] : {}", userTask.getId(), e.getMessage());
        executeWithResult.taskNotFound = true;
        return executeWithResult;
      }
    }

    // Now, we block the thread and wait for a result
    workerImplementation.waitForResult(lockObjectTransporter, timeoutDurationInMs);


    // retrieve the taskId where the currentprocess instance is
    executeWithResult.elementId = lockObjectTransporter.elementId;
    executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

    // we got the result
    // we can close the worker now
    workerImplementation.closeTransaction(lockObjectTransporter);

    Long endTime = System.currentTimeMillis();
    executeWithResult.processInstance = Long.valueOf(userTask.getProcessInstanceKey());
    executeWithResult.executionTime = endTime - beginTime;

    if (lockObjectTransporter.notification) {
      executeWithResult.timeOut = false;
      executeWithResult.processVariables = lockObjectTransporter.processVariables;
      String doubleCheckAnalysis = "";
      if (doubleCheck) {
        String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
        Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
        doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(snitchValue) ?
                String.format("Snitch_Different(snitch[%1d] SnichProcess[%2d])", snitchValue, snitchProcess) :
                "Snitch_marker_OK";
      }
      logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", jobKey, endTime - beginTime,
              timeoutDurationInMs, userTask.getProcessInstanceKey(), doubleCheckAnalysis,
              lockObjectTransporter.processVariables);
    } else {
      executeWithResult.timeOut = true;
      executeWithResult.processVariables = null;

      logger.debug("RESULT TIMEOUT  JobKey[{}]  in {} ms (timeout {} ms) Pid[{}] ", jobKey, endTime - beginTime,
              timeoutDurationInMs, userTask.getProcessInstanceKey());
    }

    return executeWithResult;
  }

  /**
   * processInstanceWithResult
   *
   * @param processId           processId to start
   * @param variables           Variables to update the task at completion
   * @param jobKey key to wait for the worker
   * @param timeoutDurationInMs maximum duration time, after the ExceptionWithResult.timeOut is true
   * @return the result status
   * @throws Exception in case of error
   */
  public ExecuteWithResult processInstanceWithResult(String processId,
                                                     Map<String, Object> variables,
                                                     String jobKey,
                                                     String prefixTopicWorker,
                                                     long timeoutDurationInMs) throws Exception {
    // We need to create a unique ID
    Long beginTime = System.currentTimeMillis();

    logger.debug("ExecuteTaskWithResult[{}]", jobKey);
    int snitchValue = random.nextInt(10000);


    // Now, create a worker just for this jobKey
    logger.debug("Register worker[{}]", "end-result-" + jobKey);
    DynamicWorker.LockObjectTransporter lockObjectTransporter= workerImplementation.openTransaction( "createProcessInstance", prefixTopicWorker, jobKey );

    Map<String, Object> userVariables = new HashMap<>();
    userVariables.put("jobKey", jobKey);
    userVariables.putAll(variables);
    ExecuteWithResult executeWithResult = new ExecuteWithResult();

    // save the variable jobId
      try {

        ProcessInstanceEvent processInstanceEvent = zeebeClient.newCreateInstanceCommand().bpmnProcessId(processId).latestVersion().variables(userVariables).send().join();
        executeWithResult.processInstance = processInstanceEvent.getProcessInstanceKey();
        // logger.info("Create process instance {} jobKey [{}]", executeWithResult.processInstance,jobKey);
      } catch (Exception e) {
        logger.error("Can't create process instance[{}] : {}", processId, e.getMessage());
        executeWithResult.creationError = true;
        return executeWithResult;
      }

    // Now, we block the thread and wait for a result
    workerImplementation.waitForResult(lockObjectTransporter, timeoutDurationInMs);

    // logger.debug("Receive answer jobKey[{}] notification? {} inprogress {}", jobKey, lockObjectTransporter.notification, lockObjectsMap.size());

    // retrieve the taskId where the currentprocess instance is
    executeWithResult.elementId = lockObjectTransporter.elementId;
    executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

    workerImplementation.closeTransaction( lockObjectTransporter);

    Long endTime = System.currentTimeMillis();
    executeWithResult.executionTime = endTime - beginTime;

    if (lockObjectTransporter.notification) {
      executeWithResult.timeOut = false;
      executeWithResult.processVariables = lockObjectTransporter.processVariables;
      String doubleCheckAnalysis = "";
      if (doubleCheck) {
        String jobKeyProcess = (String) lockObjectTransporter.processVariables.get("jobKey");
        Integer snitchProcess = (Integer) lockObjectTransporter.processVariables.get(PROCESS_VARIABLE_SNITCH);
        doubleCheckAnalysis = snitchProcess == null || !snitchProcess.equals(snitchValue) ?
                String.format("Snitch_Different(snitch[%1d] SnichProcess[%2d])", snitchValue, snitchProcess) :
                "Snitch_marker_OK";
      }
      logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", jobKey, endTime - beginTime,
              timeoutDurationInMs, executeWithResult.processInstance, doubleCheckAnalysis,
              lockObjectTransporter.processVariables);
    } else {
      executeWithResult.timeOut = true;
      executeWithResult.processVariables = null;

      logger.debug("RESULT TIMEOUT  JobKey[{}]  in {} ms (timeout {} ms) Pid[{}] ", jobKey, endTime - beginTime,
              timeoutDurationInMs, executeWithResult.processInstance);
    }

    return executeWithResult;

  }


  /**
   * Publish message
   * @param messageName
   * @param variables
   * @param timeoutDurationInMs
   * @return
   * @throws Exception
   */
  public ExecuteWithResult publishNewMessageWithResult(String messageName,
                                                     String correlationKey,
                                                     Duration timeToLive,
                                                     Map<String, Object> variables,
                                                     String jobKey,
                                                     String prefixTopicWorker,
                                                     long timeoutDurationInMs) throws Exception {
    // We need to create a unique ID
    Long beginTime = System.currentTimeMillis();

    logger.debug("publishNewMessageWithResult[{}]", correlationKey);


    // Now, create a worker just for this jobKey
    logger.debug("Register worker[{}]", "end-result-" + jobKey);
    DynamicWorker.LockObjectTransporter lockObjectTransporter= workerImplementation.openTransaction("publishMessage", prefixTopicWorker, jobKey );

    Map<String, Object> messageVariables = new HashMap<>();
    messageVariables.put("jobKey", jobKey);
    messageVariables.putAll(variables);
    ExecuteWithResult executeWithResult = new ExecuteWithResult();

    // save the variable jobId
    try {

      PublishMessageResponse publishMessageResponse = zeebeClient.newPublishMessageCommand()
              .messageName(messageName)
              .correlationKey(correlationKey)
              .variables(messageVariables)
              .timeToLive( timeToLive)
              .send()
              .join();


      // logger.info("Create process instance {} jobKey [{}]", executeWithResult.processInstance,jobKey);
    } catch (Exception e) {
      logger.error("Can't send message[{}] : {}", messageName, e.getMessage());
      executeWithResult.messageError = true;
      return executeWithResult;
    }

    // Now, we block the thread and wait for a result
    workerImplementation.waitForResult(lockObjectTransporter, timeoutDurationInMs);

    // logger.debug("Receive answer jobKey[{}] notification? {} inprogress {}", jobKey, lockObjectTransporter.notification, lockObjectsMap.size());

    // retrieve the taskId where the currentprocess instance is
    executeWithResult.elementId = lockObjectTransporter.elementId;
    executeWithResult.elementInstanceKey = lockObjectTransporter.elementInstanceKey;

    workerImplementation.closeTransaction( lockObjectTransporter);

    Long endTime = System.currentTimeMillis();
    executeWithResult.executionTime = endTime - beginTime;

    if (lockObjectTransporter.notification) {
      executeWithResult.timeOut = false;
      executeWithResult.processVariables = lockObjectTransporter.processVariables;
      String doubleCheckAnalysis = "";
      logger.debug("RESULT JobKey[{}] in {} ms (timeout {} ms) Pid[{}] {} variables[{}]", jobKey, endTime - beginTime,
              timeoutDurationInMs, executeWithResult.processInstance, doubleCheckAnalysis,
              lockObjectTransporter.processVariables);
    } else {
      executeWithResult.timeOut = true;
      executeWithResult.processVariables = null;

      logger.debug("RESULT TIMEOUT  JobKey[{}]  in {} ms (timeout {} ms) Pid[{}] ", jobKey, endTime - beginTime,
              timeoutDurationInMs, executeWithResult.processInstance);
    }

    return executeWithResult;

  }

}
