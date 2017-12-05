package com.nee.it.jira;

/**
 * Service object to interact with JIRA.
 *
 * @author Sean Ford
 * @since 2013-10-26
 */
public interface JiraService {

    public String getIssueState(String issueKey);
}
