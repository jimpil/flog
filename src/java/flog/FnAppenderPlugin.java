package flog;

import org.apache.logging.log4j.plugins.model.PluginEntry;
import org.apache.logging.log4j.plugins.model.PluginService;

public class FnAppenderPlugin extends PluginService {
    private static final PluginEntry[] ENTRIES = new PluginEntry[] {
            PluginEntry.builder()
                    .setKey("clojure")
                    .setClassName("flog.FnAppender")
                    .setName("Clojure")
                    .setNamespace("Core")
                    .setElementType("appender")
                    .get()
    };
    @Override
    public PluginEntry[] getEntries() { return ENTRIES; }
}
