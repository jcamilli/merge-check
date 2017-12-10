package com.nee.it.hook;

import com.atlassian.applinks.api.ApplicationLink;
import com.atlassian.applinks.api.ApplicationLinkRequest;
import com.atlassian.applinks.api.ApplicationLinkService;
import com.atlassian.applinks.api.CredentialsRequiredException;
import com.atlassian.applinks.api.application.jira.JiraApplicationType;
import com.atlassian.bitbucket.hook.repository.RepositoryMergeRequestCheck;
import com.atlassian.bitbucket.hook.repository.RepositoryMergeRequestCheckContext;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestParticipant;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.setting.RepositorySettingsValidator;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.sal.api.component.ComponentLocator;
import com.atlassian.sal.api.net.Request;
import com.atlassian.sal.api.net.ResponseException;
import com.atlassian.sal.api.net.ResponseStatusException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nee.it.utils.PluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BusinessApprovedHook implements RepositoryMergeRequestCheck, RepositorySettingsValidator
{
    private static final Logger log = LoggerFactory.getLogger(BusinessApprovedHook.class);

    final static String FIELD_APPROVED_STATES = "approved-states";

    final static String FIELD_REVIEWER_COUNT = "approvers";
    final static int FIELD_REVIEWER_COUNT_DEFAULT = 999;

    /**
     * Vetos a pull-request if all issues are not approved.
     * No veto if we have enough approvers to override.
     */
    public void check(RepositoryMergeRequestCheckContext context)
    {
        // If 1 or more errors, merge will be vetoed.
        ArrayList<String> errors = new ArrayList<String>();

        PullRequest pullrequest = context.getMergeRequest().getPullRequest();

        // ----------------------------------------------------------------------------------
        // Check Reviewer Count
        int overrideCount = getIntegerFromContextSettings(context, FIELD_REVIEWER_COUNT, FIELD_REVIEWER_COUNT_DEFAULT);

        // If we are overridden by reviewers, skip check of business approvals.
        if(isApprovalOverriddenByReviewers(pullrequest.getReviewers(), overrideCount) == false) {

            // ------------------------------------------------------------------------------
            // Check if issues in title are approved.
            checkIfIssuesApproved(context, errors, pullrequest);
        }

        // Veto happens here.
        if(errors.size() > 0)
        {
            vetoMergeIfErrors(context, errors);
        }
    }

    /**
     * If any issues specified in pull request title are not approved, an error will be
     * added to parameter errors.
     *
     * @param context Pull request context
     * @param errors
     * @param pullrequest
     */
    private void checkIfIssuesApproved(RepositoryMergeRequestCheckContext context, ArrayList<String> errors, PullRequest pullrequest) {

        String prTitle = pullrequest.getTitle();

        List<String> issues = getIssuesFromTitle(prTitle);

        if (issues.size() == 0) {

            errors.add("Please add all business approved Jira issues to Pull-Request title.");

        } else {

            String[] approvals = getApproversFromContextSetting(FIELD_APPROVED_STATES, context);

            List<String> newErrors = isIssuesApproved(issues, approvals);

            errors.addAll(newErrors);
        }
    }

    /**
     * Get an integer from the context while
     *
     * @param context Context of PR
     * @param defaultValue Default value if key cannot be found.
     * @return default value if cannot find key. Else return key's value.
     */
    private int getIntegerFromContextSettings(RepositoryMergeRequestCheckContext context, String key, int defaultValue) {

        Integer i = context.getSettings().getInt(key);

        if(i == null)
        {
            return defaultValue;
        }

        return i;
    }

    /**
     * Vetos the merge request if there are any errors reported (errors.size() > 0)
     * @param context Context of merge request.
     * @param errors Errors found.
     */
    private void vetoMergeIfErrors(RepositoryMergeRequestCheckContext context, ArrayList<String> errors) {

        if (errors.size() > 0) {

            String errorMsg = "";

            for (String error : errors) {
                errorMsg += " " + error;
            }
            // Chop off first space.
            errorMsg = errorMsg.substring(1);


            context.getMergeRequest().veto("Issues not approved.", errorMsg);
        }
    }

    /**
     * Return true if number of approvals is >= override count.
     * @param reviewers Reviewers for this pull request.
     * @param overrideCount Override count from hook settings.
     * @return True if overridden. False otherwise.
     */
    private boolean isApprovalOverriddenByReviewers(Set<PullRequestParticipant> reviewers, int overrideCount) {

        int reviewApprovalCount = 0;

        for (PullRequestParticipant reviewer : reviewers)
        {
            if(reviewer.isApproved()) {

                reviewApprovalCount++;
            }
        }

        return reviewApprovalCount >= overrideCount;
    }

    /**
     * Helper to get approvers from context setting.
     * @param key The approvers key.
     * @param context Context with settings.
     * @return Null if errors. empty list if no reviewers.
     */
    private String[] getApproversFromContextSetting(String key, RepositoryMergeRequestCheckContext context) {

        String approvalsString = context.getSettings().getString(key);
        return parseApprovals(approvalsString);
    }

    /**
     * Parse approvals from approvals string provided by user.
     * @return array of approved states for this repository.
     */
    private String[] parseApprovals(String approvalsString) {

        if (approvalsString != null) {

            return approvalsString.split(",");
        }

        return null;
    }

    /**
     * Returns error messages if any issues.
     * Empty list if no error found and all issues have been approved.
     */
    private List<String> isIssuesApproved(List<String> issues, String[] approvedStates) {

        ArrayList<String> errors = new ArrayList<String>();

        for(String issue : issues)
        {
            // Make jira GET rest-call...
            JsonObject issueJson = jiraRestGET(errors, "/rest/api/2/issue/" + issue );

            if (issueJson != null)
            {
                String status = getIssueStatus(errors, issue, issueJson);

                if (statusIsApproved(status, approvedStates) == false)
                {
                    errors.add(issue + " is not business approved.");
                }
            }

            // if we get a null issue, just stop.
            // User needs to fix their PR title.
            else {
                log.error("Null json response from issue " + issue);

                break;
            }
        }

        return errors;
    }

    /**
     * Get issue status.
     *
     * @param errors
     * @param issue
     * @param issueJson
     * @return
     */
    private String getIssueStatus(ArrayList<String> errors, String issue, JsonObject issueJson) {

        String status = null;

        // Get issue status
        String[] keys = {"fields", "status", "name"};

        JsonElement statusElement = getValueFromJsonElement(issueJson, keys);

        if (statusElement == null) {
            errors.add(issue + " status could not be found.");
        }
        else {
            status = statusElement.toString();
            status = status.replaceAll("^\"|\"$", "");
        }

        return status;
    }

    /**
     * Make a rest call to JIRA and returns JSON response.
     * @param errors The errors found, if any.
     * @param url URL to use for JIRA REST GET call.
     * @return JsonObject of response. Null if any errors.
     */
    private JsonObject jiraRestGET(ArrayList<String> errors, String url) {

        ApplicationLink jiraAppLink = getJiraApplicationLink();

        log.debug("Fetching from app link {}, {}", jiraAppLink.getName(), url);

        try {

            ApplicationLinkRequest req = jiraAppLink.createAuthenticatedRequestFactory()
                    .createRequest(Request.MethodType.GET, url);

            req.setHeader("Content-Type", "application/json");

            String jsonResponse = req.execute();

            log.debug("json response: {}", jsonResponse);

            JsonObject response = new JsonParser().parse(jsonResponse).getAsJsonObject();

            return response;

        } catch (CredentialsRequiredException e) {

            String errMsg = "Invalid credentials for application link " + jiraAppLink.getName();
            log.error(errMsg);
            errors.add(errMsg);

        } catch (ResponseException e) {
            String errMsg = "Invalid response returned from " + jiraAppLink.getName();

            log.error(errMsg, e);
            errors.add(errMsg);

            if (e instanceof ResponseStatusException) {
                ResponseStatusException statusException = (ResponseStatusException) e;

                log.debug("Status code {}", statusException.getResponse().getStatusCode(), e);

                try {

                    log.debug("Response entity: {}", statusException.getResponse().getResponseBodyAsString());

                } catch (ResponseException e1) {
                    log.error("Error getting response body", e);
                }

                if (statusException.getResponse().getStatusCode() == 400) {
                    log.error("Query is not valid for JIRA instance.");
                }
            }
        }

        return null;
    }

    /**
     * Returns true if status string matches any strings in approvedStates array
     * @param status Status to check. NUll is ok. Will just return false.
     * @param approvedStates List of approved states.
     * @return True if match found. False otherwise.
     */
    private boolean statusIsApproved(String status, String[] approvedStates) {

        if (status != null) {

            for (String approvedState : approvedStates) {

                if (status.equalsIgnoreCase(approvedState))
                    return true;
            }
        }

        return false;
    }

    /**
     * Returns the value of the key provided.
     * @param element
     * @param key
     * @return Null if no key found.
     */
    private JsonElement getValueFromJsonElement(JsonElement element, String key)
    {
        // JsonObject response = new JsonParser().parse(jsonResponse).getAsJsonObject();
        // JsonArray issues = response.get("issues").getAsJsonArray();

        if(element != null)
        {
            if(element instanceof JsonObject)
            {
                return ((JsonObject) element).get(key);
            }
            else if (element instanceof JsonArray)
            {
                // array not supported.
                return null;
            }
        }

        return null;
    }

    /**
     * Iterates through the keys provided and returns the value associated with the last sub-key.
     * @param element The json element to search through
     * @param keys The parent, child, etc... keys.
     * @return The value of the last key element found. Null if no key found.
     */
    private JsonElement getValueFromJsonElement(JsonElement element, String[] keys)
    {
        for (String key : keys)
        {
            if (element == null)
                break;

            element = getValueFromJsonElement(element, key);
        }

        return element;
    }

    /**
     * Returns list of issues found in the pull request title.
     * @param prTitle Title of pull request.
     * @return Empty string if none found.
     */
    private List<String> getIssuesFromTitle(String prTitle) {

        // We only want the portion of the title before the first ":" character
        String issueString = getIssuesStringFromTitle(prTitle);

        // To match all jira numbers.
        String regexPattern = "((([a-z]+)|([A-Z]+))-[0-9]+)";

        Matcher m = Pattern.compile(regexPattern).matcher(issueString);

        ArrayList<String> issueList = new ArrayList<String>();
        while (m.find())
        {
            String found = m.group();

            if(PluginUtils.ifNotNullOrEmpty(found))
            {
                issueList.add(found);
            }
        }

        log.debug("Found " + issueList.size() + " issues in " + prTitle);

        return issueList;
    }


    /**
     * Returns the issue string from the PR title. Null if no ":" found or no issues found.
     */
    private String getIssuesStringFromTitle(String title)
    {
        String rtn = null;

        int colonIndex = title.indexOf(":");

        // Need at least 2 characters to be an issue...
        if (colonIndex > 2) {
            rtn = title.substring(0, colonIndex); //this will give abc
        }
        else
        {
            // If we don't find a ":", then just return the whole string.
            rtn = title;
        }

        return rtn;
    }


    /**
     * Returns the jira application link connected to this instance of bitbucket.
     * @return jira app link. Exception if any errors.
     */
    private ApplicationLink getJiraApplicationLink() {

        ApplicationLink linkRtn = null;

        ApplicationLinkService appLinkService = ComponentLocator.getComponent(ApplicationLinkService.class);

        for(ApplicationLink link : appLinkService.getApplicationLinks(JiraApplicationType.class)) {

            // We do not support more than 1 instance of JIRA
            linkRtn = link;
            break;
        }

        if (linkRtn == null)
        {
            throw new IllegalStateException("No JIRA application links exist.");
        }

        return linkRtn;
    }

    /**
     * Called when Save is pressed from Hook settings dialog box.
     * @param settings Merge settings.
     * @param errors If any errors returned, save not allowed.
     * @param repository The repo from which this hook is called.
     */
    public void validate(Settings settings, SettingsValidationErrors errors, Repository repository)
    {
        String numReviewersString = settings.getString(FIELD_REVIEWER_COUNT, "0").trim();

        if (numReviewersString.length() == 0)
        {
            // This is ok. ignore.
        }
        else if (!NUMBER_PATTERN.matcher(numReviewersString).matches())
        {
            errors.addFieldError(FIELD_REVIEWER_COUNT, "Enter a valid number");
        }
        else if (Integer.parseInt(numReviewersString) < 0)
        {
            errors.addFieldError(FIELD_REVIEWER_COUNT, "Number of reviewers must be greater or equal to zero");
        }
    }

    Pattern NUMBER_PATTERN = Pattern.compile("^\\d{1,10}$");
}
