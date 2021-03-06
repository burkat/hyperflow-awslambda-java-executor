import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.IOUtils;
import com.google.gson.Gson;
import payloads.Event;
import payloads.Request;
import payloads.Response;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Handler {

    private static final String TMP_PATH = "/tmp/";
    private static final String BUCKET_KEY = "bucket";
    private static final String PREFIX_KEY = "prefix";
    private static final Metrics metrics = new Metrics();

    public Response handleRequest(Event event, Context context) {

        metrics.lambdaStart = Instant.now();
        LambdaLogger logger = context.getLogger();
        logger.log("body:       " + event.getBody());

        Gson gson = new Gson();
        Request request = gson.fromJson(event.getBody(), Request.class);

        try {
            logger.log("executable: " + request.getExecutable());
            logger.log("args:       " + request.getArgs());
            logger.log("inputs:     " + request.getInputs());
            logger.log("outputs:    " + request.getOutputs());
            logger.log("bucket:     " + request.getOptions().get(BUCKET_KEY));
            logger.log("prefix:     " + request.getOptions().get(PREFIX_KEY));

            downloadData(request, logger);
            downloadExecutable(request, logger);
            execute(createCommand(request), logger);
            uploadData(request, logger);
        } catch (Exception e) {
            context.getLogger().log("Execution failed.");
            return handleException(e, request);
        }
        metrics.lambdaEnd = Instant.now();
        final String metricsString = "lambda start: " + metrics.lambdaStart.toEpochMilli() + " lambda end: " + metrics.lambdaEnd.toEpochMilli() +
                " download start: " + metrics.downloadStart.toEpochMilli() + " download end: " + metrics.downloadEnd.toEpochMilli() +
                " execution start: " + metrics.executionStart.toEpochMilli() + " execution end: " + metrics.executionEnd.toEpochMilli() +
                " upload start: " + metrics.uploadStart.toEpochMilli() + " upload end: " + metrics.uploadEnd.toEpochMilli();
        if (request.getLogName() != null) {
            S3Utils.S3.putObject(request.getOptions().get(BUCKET_KEY), "logs/" + request.getLogName(), metricsString);
        }
        Response response = new Response();
        response.setStatusCode(HttpURLConnection.HTTP_OK);
        response.setBody(metricsString);
        return response;
    }

    private void downloadData(Request request, LambdaLogger logger) throws IOException {
        metrics.downloadStart = Instant.now();
        for (Map<String, Object> input : request.getInputs()) {
            String fileName = input.get("name").toString();
            String key = request.getOptions().get(PREFIX_KEY) + "/" + fileName;
            logger.log("Downloading file: " + request.getOptions().get(BUCKET_KEY) + "/" + key);
            S3Object s3Object = S3Utils.getObject(request.getOptions().get(BUCKET_KEY), key);
            S3Utils.saveToFile(s3Object, TMP_PATH + fileName);
        }
    }

    private void downloadExecutable(Request request, LambdaLogger logger) throws IOException {
        logger.log("Downloading executable file: " + request.getExecutable());
        String key = request.getOptions().get(PREFIX_KEY) + "/" + request.getExecutable();
        S3Object s3Object = S3Utils.getObject(request.getOptions().get(BUCKET_KEY), key);
        S3Utils.saveToFile(s3Object, TMP_PATH + request.getExecutable());
        metrics.downloadEnd = Instant.now();
    }

    private List<String> createCommand(Request request) {
        List<String> command = new ArrayList<>();
        command.add(TMP_PATH + request.getExecutable());
        command.addAll(request.getArgs());
        return command;
    }

    private void execute(List<String> command, LambdaLogger logger) throws Exception {
        metrics.executionStart = Instant.now();
        logger.log("Executing: " + String.join(" ", command));

        Process chmod = Runtime.getRuntime().exec("chmod -R 777 " + TMP_PATH);
        chmod.waitFor();

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(new File(TMP_PATH));
        Process process = processBuilder.start();

        String outputMsg = IOUtils.toString(process.getInputStream());
        String errorMsg = IOUtils.toString(process.getErrorStream());

        logger.log("Stdout: " + outputMsg);
        logger.log("Stderr: " + errorMsg);
        metrics.executionEnd = Instant.now();
    }

    private void uploadData(Request request, LambdaLogger logger) {
        metrics.uploadStart = Instant.now();
        for (Map<String, String> input : request.getOutputs()) {
            String fileName = input.get("name");
            String filePath = TMP_PATH + fileName;
            String key = request.getOptions().get(PREFIX_KEY) + "/" + fileName;
            logger.log("Uploading to " + request.getOptions().get(BUCKET_KEY) + "/" + key + " from " + filePath);
            S3Utils.putObject(request.getOptions().get(BUCKET_KEY), key, filePath);
        }
        metrics.uploadEnd = Instant.now();
    }

    private Response handleException(Exception e, Request request) {
        Throwable cause;
        if (e.getCause() != null) {
            cause = e.getCause();
        } else {
            cause = e;
        }
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        Response response = new Response();
        response.setStatusCode(HttpURLConnection.HTTP_INTERNAL_ERROR);
        response.setBody("Execution of " + request.getExecutable() + " failed, cause: " + cause.getMessage());
        return response;
    }
}
