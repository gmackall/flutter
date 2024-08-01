package com.flutter.gradle;

public class Deeplink {
    public boolean equals(Object o) {
        if (o == null) {
            // TODO(gmackall): Leaving this as is for this PR as I want to keep the scope to the
            // groovy -> Java conversion, but this is surprising behavior for a .equals method.
            throw new NullPointerException();
        }

        if (!o.getClass().equals(getClass())) {
            return false;
        }

        Deeplink oAsDeepLink = (Deeplink) o;

        return scheme.equals(oAsDeepLink.scheme)
                && host.equals(oAsDeepLink.host)
                && path.equals(oAsDeepLink.path);
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public IntentFilterCheck getIntentFilterCheck() {
        return intentFilterCheck;
    }

    public void setIntentFilterCheck(IntentFilterCheck intentFilterCheck) {
        this.intentFilterCheck = intentFilterCheck;
    }

    private String scheme;
    private String host;
    private String path;
    private IntentFilterCheck intentFilterCheck;
}
