package io.camunda.loadtest.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * THis object log a message, but do that only if the last information was before 10 s, in order to not bother all the output
 */
public class LoggerMessage {
    Logger logger = LoggerFactory.getLogger(LoggerMessage.class.getName());

    final private String header;
    private long lastMessage=0;

    public LoggerMessage(String header) {
        this.header = header+" ";

    }

    public void message(String message) {
        if (! canLog())
            return;
        logger.info( header+message);
        lastMessage=System.currentTimeMillis();
    }
    public boolean canLog() {
        return System.currentTimeMillis()-lastMessage > 50000;
    }
}
