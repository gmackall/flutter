package com.flutter.gradle;

import java.util.Set;


public class AppLinkSettings {
    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public Set<Deeplink> getDeeplinks() {
        return deeplinks;
    }

    public void setDeeplinks(Set<Deeplink> deeplinks) {
        this.deeplinks = deeplinks;
    }

    public boolean getDeeplinkingFlagEnabled() {
        return deeplinkingFlagEnabled;
    }

    public boolean isDeeplinkingFlagEnabled() {
        return deeplinkingFlagEnabled;
    }

    public void setDeeplinkingFlagEnabled(boolean deeplinkingFlagEnabled) {
        this.deeplinkingFlagEnabled = deeplinkingFlagEnabled;
    }

    private String applicationId;
    private Set<Deeplink> deeplinks;
    private boolean deeplinkingFlagEnabled;
}
