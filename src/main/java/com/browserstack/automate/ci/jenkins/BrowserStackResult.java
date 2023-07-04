package com.browserstack.automate.ci.jenkins;

import com.browserstack.automate.ci.common.constants.Constants;
import hudson.tasks.test.TestObject;
import hudson.tasks.test.TestResult;

public class BrowserStackResult extends TestResult {
    @Override
    public TestObject getParent() {
        return null;
    }

    @Override
    public TestResult findCorrespondingResult(String id) {
        return null;
    }

    @Override
    public String getDisplayName() {
        return Constants.BROWSERSTACK_REPORT_DISPLAY_NAME;
    }
}
