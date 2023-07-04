package com.browserstack.automate.ci.jenkins;

import com.browserstack.automate.ci.common.constants.Constants;
import hudson.model.Action;
import hudson.model.Run;
import hudson.tasks.test.AbstractTestResultAction;
import jenkins.tasks.SimpleBuildStep;

import java.io.Serializable;
import java.util.logging.Logger;

public abstract class AbstractBrowserStackReportForBuild extends AbstractTestResultAction {
    private Run<?, ?> build;
    private static final Logger LOGGER = Logger.getLogger(AbstractBrowserStackReportForBuild.class.getName());


    @Override
    public String getIconFileName() {
        return Constants.BROWSERSTACK_LOGO;
    }

    @Override
    public String getDisplayName() {
        LOGGER.info("Inside getDisplayName");
        return Constants.BROWSERSTACK_REPORT_DISPLAY_NAME;
    }

    @Override
    public String getUrlName() {
        LOGGER.info("Inside getUrlName");
        return Constants.BROWSERSTACK_REPORT_URL;
    }

    public Run<?, ?> getBuild() {
        return build;
    }

    public void setBuild(Run<?, ?> build) {
        this.build = build;
    }
}
