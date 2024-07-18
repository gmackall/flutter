import groovy.transform.CompileStatic

@CompileStatic
class AppLinkSettings {

    String applicationId
    Set<Deeplink> deeplinks
    boolean deeplinkingFlagEnabled

}