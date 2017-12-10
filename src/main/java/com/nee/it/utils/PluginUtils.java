package com.nee.it.utils;

/**
 * Utility methods for plugin merge hook...
 */
public class PluginUtils {

    public static boolean ifNotNullOrEmpty(String str)
    {
        if(str != null && (str.isEmpty() == false))
        {
            return true;
        }

        return false;
    }

    public static String trimStringByString(String text, String trimBy)
    {
        int beginIndex = 0;
        int endIndex = text.length();

        while (text.substring(beginIndex, endIndex).startsWith(trimBy)) {

            beginIndex += trimBy.length();
        }

        while (text.substring(beginIndex, endIndex).endsWith(trimBy)) {

            endIndex -= trimBy.length();
        }

        return text.substring(beginIndex, endIndex);
    }
}
