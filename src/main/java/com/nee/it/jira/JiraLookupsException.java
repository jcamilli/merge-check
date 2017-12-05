package com.nee.it.jira;

import com.atlassian.applinks.api.CredentialsRequiredException;
import com.atlassian.applinks.api.ReadOnlyApplicationLink;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Bradley Baetz
 */
class JiraLookupsException extends Exception {
    private final Map<ReadOnlyApplicationLink, String> errors = new LinkedHashMap<>();

    public JiraLookupsException() {

    }

    public void addError(ReadOnlyApplicationLink link, String message) {
        errors.put(link, message);
    }

    public void addError(ReadOnlyApplicationLink link, Exception ex) {
        String error;

        if (ex instanceof CredentialsRequiredException) {
            CredentialsRequiredException credentialsRequiredException = (CredentialsRequiredException) ex;
            error = "Could not authenticate. Visit "
                    + credentialsRequiredException.getAuthorisationURI().toASCIIString()
                    + " to link your Stash account to your JIRA account";
        }
        else {
            error = "Internal error: " + ex.getMessage() + ". Check server logs for details.";
        }

        errors.put(link, error);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void addMessageForApplicationLinksNotPresent(Iterable<ReadOnlyApplicationLink> links, String message) {
        for (ReadOnlyApplicationLink link : links) {
            if (!errors.containsKey(link)) {
                errors.put(link, message);
            }
        }
    }
}