package com.nee.it.jira;

import com.atlassian.applinks.api.*;
import com.atlassian.applinks.api.application.jira.JiraApplicationType;
import com.atlassian.sal.api.net.Request;
import com.atlassian.sal.api.net.ResponseException;
import com.atlassian.sal.api.net.ResponseStatusException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Sean Ford
 * @since 2013-10-20
 */
public class JiraServiceImpl implements JiraService {
    private static final Logger log = LoggerFactory.getLogger(JiraServiceImpl.class);

    private static final String ISSUE_NOT_FOUND = "%s: JIRA Issue does not exist";
    private static final String JQL_NO_MATCH = "%s: JIRA Issue does not match JQL Query: %s";

    private final ApplicationLinkService applicationLinkService;

    public JiraServiceImpl(ApplicationLinkService applicationLinkService) {

        this.applicationLinkService = applicationLinkService;
    }

    private Iterable<ReadOnlyApplicationLink> getJiraApplicationLinks() {
        List<ReadOnlyApplicationLink> links = new ArrayList<>();

        for (ApplicationLink link : applicationLinkService.getApplicationLinks(JiraApplicationType.class)) {
            links.add(link);
        }

        if (links.isEmpty()) {
            throw new IllegalStateException("No JIRA application links exist.");
        }

        log.debug("number of JIRA application links: {}", links.size());

        return links;
    }

    public String getIssueState(String issueKey)
    {
        String state = null;

        JiraLookupsException ex = new JiraLookupsException();

        for (final ReadOnlyApplicationLink link : getJiraApplicationLinks()) {

            try {

                log.debug("Finding issue '{}' for JIRA application link '{}'", issueKey, link.getName());

                ApplicationLinkRequest req = link.createAuthenticatedRequestFactory()
                        .createRequest(Request.MethodType.POST, "/rest/api/2/issue/" + issueKey);

                req.setHeader("Content-Type", "application/json");

                String jsonResponse = req.execute();

                log.debug("json response: {}", jsonResponse);

                JsonObject response = new JsonParser().parse(jsonResponse).getAsJsonObject();
                JsonArray issues = response.get("issues").getAsJsonArray();

            } catch (CredentialsRequiredException e) {

                log.error("credentials", e);
                ex.addError(link, e);

            } catch (ResponseException e) {
                if (e instanceof ResponseStatusException) {
                    ResponseStatusException statusException = (ResponseStatusException) e;

                    log.debug("status code {}", statusException.getResponse().getStatusCode(), e);

                    try {
                        log.debug("response entity: {}", statusException.getResponse().getResponseBodyAsString());
                    } catch (ResponseException e1) {
                        log.error("error getting response body", e);
                    }

                    // Not found??
                    if (statusException.getResponse().getStatusCode() == 400) {

                        continue;
                    }
                }

                log.error("response", e);

                ex.addError(link, e);
            }
        }

        return state;

    }

    /*
    private boolean execute(String jqlQuery, SUCCESS_ON successOn, boolean trackInvalidJqlAsError)
            throws JiraLookupsException {
        checkNotNull(jqlQuery, "jqlQuery is null");

        JiraLookupsException ex = new JiraLookupsException();

        for (final ReadOnlyApplicationLink link : getJiraApplicationLinks()) {
            try {
                log.debug("executing JQL query on JIRA application link '{}': {}", link.getName(),
                        jqlQuery);

                ApplicationLinkRequest req = link.createAuthenticatedRequestFactory()
                        .createRequest(Request.MethodType.POST, "/rest/api/2/search");

                req.setHeader("Content-Type", "application/json");

                Map<String, Object> request = new HashMap<>();
                request.put("jql", jqlQuery);

                List<String> requestedFields = new ArrayList<>();
                requestedFields.add("summary");
                request.put("fields", requestedFields);

                req.setEntity(new Gson().toJson(request));

                String jsonResponse = req.execute();

                log.debug("json response: {}", jsonResponse);

                JsonObject response = new JsonParser().parse(jsonResponse).getAsJsonObject();
                JsonArray issues = response.get("issues").getAsJsonArray();

                if (successOn == SUCCESS_ON.NON_ZERO_RESULT && issues.size() > 0) {
                    return true;
                }
                else if (successOn == SUCCESS_ON.STATUS_200) {
                    return true;
                }
            } catch (CredentialsRequiredException e) {
                log.error("credentials", e);

                ex.addError(link, e);
            } catch (ResponseException e) {
                if (e instanceof ResponseStatusException) {
                    ResponseStatusException statusException = (ResponseStatusException) e;

                    log.debug("status code {}", statusException.getResponse().getStatusCode(), e);

                    try {
                        log.debug("response entity: {}", statusException.getResponse().getResponseBodyAsString());
                    } catch (ResponseException e1) {
                        log.error("error getting response body", e);
                    }

                    if (statusException.getResponse().getStatusCode() == 400) {
                        if(trackInvalidJqlAsError) {
                            ex.addError(link, "Query is not valid for JIRA instance: " + jqlQuery);
                        }

                        continue;
                    }
                }

                log.error("response", e);

                ex.addError(link, e);
            }
        }

        if (ex.hasErrors()) {
            throw ex;
        }

        return false;
    }
    */

    private enum SUCCESS_ON {STATUS_200, NON_ZERO_RESULT}
}
