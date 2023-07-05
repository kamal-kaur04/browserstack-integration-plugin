package com.browserstack.automate.ci.jenkins;

import java.util.List;
import java.util.Map;
import org.json.JSONObject;

import com.browserstack.automate.ci.common.constants.Constants;
import hudson.tasks.test.TestObject;
import hudson.tasks.test.TestResult;

public class BrowserStackResult extends TestResult {
    private transient List<JSONObject> result;
    private Map<String, String> resultAggregation;

    public BrowserStackResult(List<JSONObject> resultList, Map<String, String> resultAggregation) {
        this.result = resultList;
        this.resultAggregation = resultAggregation;
    }
    
    public List<JSONObject> getResult() {
        return result;
    }
    
    public Map<String, String> getResultAggregation() {
        return resultAggregation;
    }
    
    @Override
    public TestObject getParent() {
        return null;
    }

    @Override
    public TestResult findCorrespondingResult(String id) {
        if (id.equals(getId())) {
            return this;
        }
        return null;
    }

    @Override
    public String getDisplayName() {
        return Constants.BROWSERSTACK_REPORT_DISPLAY_NAME;
    }
}
