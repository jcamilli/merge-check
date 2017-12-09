package com.nee.it.utils;

public class PluginUtils {

    public static boolean ifNotNullOrEmpty(String str)
    {
        if(str != null && (str.isEmpty() == false))
        {
            return true;
        }

        return false;
    }
}
