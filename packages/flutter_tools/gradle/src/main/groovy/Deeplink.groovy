import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

@CompileStatic
class Deeplink {
    String scheme, host, path
    IntentFilterCheck intentFilterCheck

    @CompileDynamic
    boolean equals(o) {
        if (o == null) {
            throw new NullPointerException()
        }
        if (o.getClass() != getClass()) {
            return false
        }
        return scheme == o.scheme &&
                host == o.host &&
                path == o.path
    }
}