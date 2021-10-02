package ca.edtoaster.impl;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class Main {
    public static void main(String[] args) {
        Environment env = new Environment(System.getenv());

        try {
            new BotRunner(env).run();
        } catch (Throwable t) {
            log.fatal("Bot runner caught exception", t);
        }
    }
}
