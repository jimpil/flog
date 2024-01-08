package flog;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.plugins.*;


@Plugin("Clojure")
@Configurable(elementType = Appender.ELEMENT_TYPE)
public class FnAppender extends AbstractAppender{
    private IFn logFn;

    private FnAppender(String name,
                       Filter filter,
                       Layout layout,
                       boolean ignoreExceptions,
                       Property[] properties) {
        super(name, filter, layout, ignoreExceptions, properties);
    }

    @PluginFactory
    public static FnAppender createAppender(@PluginElement("Layout") Layout layout,
                                            @PluginElement("Filters") final Filter filter,
                                            @PluginAttribute("name") final String name,
                                            @PluginAttribute("fn") final String cljfn) {

        if (name == null) {
            LOGGER.error("No name provided for the Clojure Appender");
            return null;
        }

        if (cljfn == null) {
            LOGGER.error("No fn provided for the Clojure Appender");
            return null;
        }

        if (layout == null) {
            LOGGER.error("Pattern layout not provided");
            return null;
        }

        FnAppender instance =  new FnAppender(name, filter, layout, true, null);
        IFn require = Clojure.var("clojure.core", "requiring-resolve");
        instance.logFn = (IFn)require.invoke(Clojure.read(cljfn));
        return instance;
    }

    public void append(LogEvent logEvent) {
        if (this.logFn != null) {
            String logLine = getLayout().toSerializable(logEvent);
            this.logFn.invoke(logLine);
        }
    }

}
