package com.charttool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ChartTool Spring Boot Application.
 */
@SpringBootApplication
public class ChartToolApplication {

    /**
     * Default constructor for the application class.
     */
    protected ChartToolApplication() {
    }

    /**
     * Boostraps the Spring application context and starts the server.
     *
     * @param args Command line arguments passed to the application.
     */
    public static void main(final String[] args) {
        SpringApplication.run(ChartToolApplication.class, args);
    }

}
