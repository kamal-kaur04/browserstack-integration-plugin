package com.browserstack.automate.ci.jenkins;

import com.browserstack.automate.ci.common.Tools;
import com.browserstack.automate.ci.common.clienthandler.ClientHandler;
import com.browserstack.automate.ci.common.constants.Constants;
import com.browserstack.automate.ci.common.enums.ProjectType;
import com.browserstack.automate.ci.common.tracking.PluginsTracker;
import com.browserstack.automate.exception.BuildNotFound;
import com.browserstack.automate.model.Build;
import com.browserstack.automate.model.Session;
import com.browserstack.client.BrowserStackClient;
import com.browserstack.client.exception.BrowserStackException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import hudson.FilePath;
import hudson.model.Run;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.annotation.Nonnull;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static com.browserstack.automate.ci.common.logger.PluginLogger.log;
import static com.browserstack.automate.ci.common.logger.PluginLogger.logError;

public class BrowserStackReportForBuild extends AbstractBrowserStackReportForBuild {
    private final String buildName;
    private final transient List<Session> browserStackSessions;
    private transient List<JSONObject> result;
    private Map<String, String> resultAggregation;
    private final ProjectType projectType;
    private final transient PrintStream logger;
    private final String customProxy;
    private final transient PluginsTracker tracker;
    private final boolean pipelineStatus;
    // to make them available in jelly
    private final String errorConst = Constants.SessionStatus.ERROR;
    private final String failedConst = Constants.SessionStatus.FAILED;
    private transient Build browserStackBuild;
    private String browserStackBuildBrowserUrl;
    private static final Logger LOGGER = Logger.getLogger(BrowserStackReportForBuild.class.getName());

    public BrowserStackReportForBuild(final Run<?, ?> build,
                                      final ProjectType projectType,
                                      final String buildName,
                                      final PrintStream logger,
                                      final PluginsTracker tracker,
                                      final boolean pipelineStatus,
                                      final String customProxy) {
        super();
        setBuild(build);
        this.buildName = buildName;
        this.browserStackSessions = new ArrayList<>();
        this.result = new ArrayList<>();
        this.resultAggregation = new HashMap<>();
        this.projectType = projectType;
        this.logger = logger;
        this.customProxy = customProxy;
        this.tracker = tracker;
        this.pipelineStatus = pipelineStatus;
        fetchBuildAndSessions();
        LOGGER.info("Is it coming here multiple time ??");
    }

    private void fetchBuildAndSessions() {
        final BrowserStackBuildAction browserStackBuildAction = getBuild().getAction(BrowserStackBuildAction.class);
        if (browserStackBuildAction == null) {
            logError(logger, "No BrowserStackBuildAction found");
            tracker.sendError("BrowserStackBuildAction Not Found", pipelineStatus, "ReportGeneration");
            return;
        }

        final BrowserStackCredentials credentials = browserStackBuildAction.getBrowserStackCredentials();
        if (credentials == null) {
            logError(logger, "BrowserStack credentials could not be fetched");
            tracker.sendError("No Credentials Available", pipelineStatus, "ReportGeneration");
            return;
        }

        tracker.setCredentials(credentials.getUsername(), credentials.getDecryptedAccesskey());

        BrowserStackClient client =
                ClientHandler.getBrowserStackClient(projectType, credentials.getUsername(), credentials.getDecryptedAccesskey(), customProxy, logger);

        browserStackBuild = fetchBrowserStackBuild(client, buildName);

        Optional.ofNullable(browserStackBuild)
                .ifPresent(browserStackBuild -> {
                    browserStackSessions.addAll(fetchBrowserStackSessions(client, browserStackBuild.getId()));
                });

        if (browserStackSessions.size() > 0) {
            String browserUrl = browserStackSessions.get(0).getBrowserUrl();
            Matcher buildUrlMatcher = Tools.BUILD_URL_PATTERN.matcher(browserUrl);
            if (buildUrlMatcher.matches()) {
                browserStackBuildBrowserUrl = buildUrlMatcher.group(1);
            }
        }
    }

    public boolean generateBrowserStackReport() {
        LOGGER.info("GENERATE BROWSERSTACK REPORT");
        if (result.size() == 0) {
            result.addAll(generateSessionsCollection(browserStackSessions));

            if (result.size() > 0) {
                result.sort(new SessionsSortingComparator());
                generateAggregationInfo();
                writeBuildResultToFile(getBuild());
                return true;
            }
            return false;
        }
        return true;
    }

    private Build fetchBrowserStackBuild(@Nonnull BrowserStackClient client, @Nonnull String buildName) {
        Build build = null;
        try {
            build = client.getBuildByName(buildName);
        } catch (BuildNotFound bnfException) {
            logError(logger, "No build found by name: " + buildName);
        } catch (BrowserStackException bstackException) {
            logError(logger, "BrowserStackException occurred while fetching build: " + bstackException.toString());
        }

        return build;
    }

    private List<Session> fetchBrowserStackSessions(@Nonnull BrowserStackClient client, @Nonnull String buildId) {
        final List<Session> browserStackSessions = new ArrayList<Session>();
        try {
            browserStackSessions.addAll(client.getSessions(buildId));
        } catch (BuildNotFound bnfException) {
            logError(logger, "No build found while fetching sessions for the buildId: " + buildId);
        } catch (BrowserStackException bstackException) {
            logError(logger, "BrowserStackException occurred while fetching sessions: " + bstackException.toString());
        }

        return browserStackSessions;
    }

    private List<JSONObject> generateSessionsCollection(List<Session> browserStackSessions) {
        return browserStackSessions.stream().map(this::convertSessionToJsonObject).collect(Collectors.toList());
    }

    private JSONObject convertSessionToJsonObject(Session session) {
        final JSONObject sessionJSON = new JSONObject();

        if (session.getName() == null || session.getName().isEmpty()) {
            sessionJSON.put(Constants.SessionInfo.NAME, session.getId());
        } else {
            sessionJSON.put(Constants.SessionInfo.NAME, session.getName());
        }

        sessionJSON.put(Constants.SessionInfo.BROWSERSTACK_BUILD_NAME, buildName);
        sessionJSON.put(Constants.SessionInfo.BROWSERSTACK_BUILD_URL, browserStackBuildBrowserUrl);

        if (session.getDevice() == null || session.getDevice().isEmpty()) {
            sessionJSON.put(Constants.SessionInfo.BROWSER, session.getBrowser());
        } else {
            sessionJSON.put(Constants.SessionInfo.BROWSER, session.getDevice());
        }
        sessionJSON.put(Constants.SessionInfo.OS, String.format("%s %s", session.getOs(), session.getOsVersion()));
        sessionJSON.put(Constants.SessionInfo.STATUS, session.getBrowserStackStatus());

        if (session.getBrowserStackStatus().equals(session.getStatus())) {
            sessionJSON.put(Constants.SessionInfo.USER_MARKED, Constants.SessionStatus.UNMARKED);
        } else {
            sessionJSON.put(Constants.SessionInfo.USER_MARKED, session.getStatus());
        }


        // Condition which shouldn't occur if the build is not being reused elsewhere.
        // But if it happens, the following condition will handle the scenario where
        // duration is null or empty (running session)
        if (Constants.SessionStatus.RUNNING.equals(session.getStatus())) {
            sessionJSON.put(Constants.SessionInfo.DURATION, "-");
        } else {
            sessionJSON.put(Constants.SessionInfo.DURATION, Tools.durationToHumanReadable(session.getDuration()));
        }

        try {
            Date sessionCreatedAt = Tools.SESSION_DATE_FORMAT.parse(session.getCreatedAt());
            sessionJSON.put(Constants.SessionInfo.CREATED_AT, sessionCreatedAt);

            String createdAtReadable = String.format("%s %s",
                    Tools.READABLE_DATE_FORMAT.format(sessionCreatedAt), "UTC");
            sessionJSON.put(Constants.SessionInfo.CREATED_AT_READABLE, createdAtReadable);
        } catch (ParseException e) {
            logError(logger, "Could not parse Session Creation Date: " + e.getMessage());
        }

        sessionJSON.put(Constants.SessionInfo.URL, String.format("%s&source=jenkins_plugin", session.getPublicUrl()));
        return sessionJSON;
    }

    private void generateAggregationInfo() {
        final int totalSessions = result.size();
        int totalErrors = 0;
        for (JSONObject session : result) {
            if (Constants.SessionStatus.ERROR.equals(session.optString(Constants.SessionInfo.STATUS))
                    || Constants.SessionStatus.FAILED.equals(session.optString(Constants.SessionInfo.USER_MARKED))) {
                totalErrors++;
            }
        }

        resultAggregation.put("totalSessions", String.valueOf(totalSessions));
        resultAggregation.put("totalErrors", String.valueOf(totalErrors));
        resultAggregation.put("buildDuration", Tools.durationToHumanReadable(browserStackBuild.getDuration()));
    }

    private String fetchBuildInfo(List<JSONObject> resultList) {
        String buildName = "";
        if (resultList.size() > 0) {
            JSONObject resultObject = resultList.get(0);
            browserStackBuildBrowserUrl = String.valueOf(resultObject.get(Constants.SessionInfo.BROWSERSTACK_BUILD_URL));
            buildName = String.valueOf(resultObject.get(Constants.SessionInfo.BROWSERSTACK_BUILD_NAME));
        }
        return buildName;
    }

    private void writeBuildResultToFile(Run<?, ?> build) {
        try {
            log(logger, getBrowserStackResult().toString());
            FilePath bstackDir = Tools.getBrowserStackReportDir(build, "browserstack-reports");
            bstackDir.mkdirs();
            FilePath dst = bstackDir.child("buildResults.json");
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("results", getBrowserStackResult());
            dst.write(jsonObject.toString(), null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<JSONObject> parseStoredBuildResult(Run<?, ?> build) {
        List<JSONObject> bstackResultList = new ArrayList<>(); 
        try {
            FilePath bstackDir = Tools.getBrowserStackReportDir(build, "browserstack-reports");
            FilePath[] paths = null;
            LOGGER.info("bstackDir " + bstackDir);

            try {
                paths = bstackDir.list("buildResults*.json");
                LOGGER.info("paths " + paths.toString());
            } catch (Exception e) {
                e.printStackTrace();
                LOGGER.info("GENERATE BROWSERSTACK REPORT " + e + "bstackDir list");
            }

            if (paths != null) {
                for (FilePath path : paths) {
                    LOGGER.info("path " + path);
                    File file = new File(path.getRemote());
                    LOGGER.info("File Path " + file.getAbsolutePath() + file.isFile());
                    
                    if (!file.isFile()) {
                        continue; // move to next file
                    } else {
                    }

                    // BufferedInputStream bufferedInputStream = null;
                    ObjectMapper mapper = new ObjectMapper();

                    try {
                        InputStream is = new FileInputStream(file);
                        String jsonTxt = IOUtils.toString(is, StandardCharsets.UTF_8);
                        // JsonParser jsonParser = new JsonParser();
                        // try {  
                        //     JSONArray jsonarray = new JSONArray(jsonTxt);
                        //     LOGGER.info("GENERATE BROWSERSTACK REPORT " + jsonarray + "parseStoredBuildResult jsonarray");
                        // } catch (Exception e) {
                        //     // TODO: handle exception
                        // }
                        JSONObject jsonObject = new JSONObject(jsonTxt);
                        JSONArray parsedResult = (JSONArray) jsonObject.get("results");
                        
                        for (int i = 0; i < parsedResult.length(); i++) {
                            JSONObject jsonobject = parsedResult.getJSONObject(i);
                            bstackResultList.add(jsonobject);
                        }
                        is.close();
                        LOGGER.info("GENERATE BROWSERSTACK REPORT " + bstackResultList + "parseStoredBuildResult Passes");
                        return bstackResultList;
                    } catch (Exception e) {
                        e.printStackTrace();
                        LOGGER.info("GENERATE BROWSERSTACK REPORT " + e + "bufferedInputStream");
                    }
                }
            }
            return bstackResultList;
            
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.info("GENERATE BROWSERSTACK REPORT " + e + "parseStoredBuildResult");
            return bstackResultList;
        }
    }

    @Override
    public int getFailCount() {
        return 0;
    }

    @Override
    public int getTotalCount() {
        return 0;
    }

    @Override
    public BrowserStackResult getResult() {
        LOGGER.info(String.format("I'm here, trying to find results %s", result));
        BrowserStackResult bstackResult = new BrowserStackResult(buildName, browserStackBuildBrowserUrl, result, resultAggregation);
        bstackResult.setRun(getBuild());
        if (result == null) {
            LOGGER.info("The result size is null");
            List<JSONObject> resultList = parseStoredBuildResult(super.run);
            try {
                if (resultList != null && resultList.size() > 0) {
                    LOGGER.info(String.format("Parse successful %s", resultList));
                    resultList.sort(new SessionsSortingComparator());
                    result = resultList;
                    resultAggregation = new HashMap<>();
                    generateAggregationInfo();
                    String browserstackbuildName = fetchBuildInfo(resultList);
                    LOGGER.info(String.format("Aggregated Report Generated %s", resultList));
                    bstackResult = new BrowserStackResult(browserstackbuildName, browserStackBuildBrowserUrl, resultList, resultAggregation);
                    bstackResult.setRun(super.run);
                }
            } catch (Exception e) {
                LOGGER.info(String.format("Exception in getResult %s", e));
            }
        }
        return bstackResult;
    }

    public List<JSONObject> getBrowserStackResult() {
        return result;
    }

    public Map<String, String> getResultAggregation() {
        return resultAggregation;
    }

    public String getBrowserStackBuildBrowserUrl() {
        return browserStackBuildBrowserUrl;
    }

    public String getBuildName() {
        return buildName;
    }

    public ProjectType getProjectType() {
        return projectType;
    }

    public String getBrowserStackBuildID() {
        if (browserStackBuild != null) {
            return browserStackBuild.getId();
        }
        return "NO_BUILD_ID";
    }

    public String getErrorConst() {
        return errorConst;
    }

    public String getFailedConst() {
        return failedConst;
    }

    private static class SessionsSortingComparator implements Comparator<JSONObject> {

        @Override
        public int compare(JSONObject sessionOne, JSONObject sessionTwo) {
            // possible values for user_marked: failed, passed and UNMARKED, thus changing all to lowercase
            final String sessionOneUserMarked = sessionOne.optString(Constants.SessionInfo.USER_MARKED).toLowerCase();
            final String sessionTwoUserMarked = sessionTwo.optString(Constants.SessionInfo.USER_MARKED).toLowerCase();
            final int userMarkedStatusComparator = sessionOneUserMarked.compareTo(sessionTwoUserMarked);

            // ascending with `user marked status` but descending with `created at`
            if (userMarkedStatusComparator == 0) {
                int createdAtComparator = 0;

                if (sessionOne.opt(Constants.SessionInfo.CREATED_AT) != null
                        && sessionTwo.opt(Constants.SessionInfo.CREATED_AT) != null) {
                    final Date sessionOneDate = (Date) sessionOne.opt(Constants.SessionInfo.CREATED_AT);
                    final Date sessionTwoDate = (Date) sessionTwo.opt(Constants.SessionInfo.CREATED_AT);
                    createdAtComparator = sessionOneDate.compareTo(sessionTwoDate);
                }

                return createdAtComparator == 0
                        ? userMarkedStatusComparator
                        : (createdAtComparator > 0 ? -1 : 1);
            }
            return userMarkedStatusComparator;
        }
    }

}
