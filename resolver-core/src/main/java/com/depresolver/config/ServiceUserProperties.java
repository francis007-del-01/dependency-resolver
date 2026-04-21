package com.depresolver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "resolver.service-user")
public class ServiceUserProperties {

    private List<String> names = new ArrayList<>();
    private List<String> emails = new ArrayList<>();

    public List<String> getNames() { return names; }
    public void setNames(List<String> names) { this.names = names != null ? names : new ArrayList<>(); }

    public List<String> getEmails() { return emails; }
    public void setEmails(List<String> emails) { this.emails = emails != null ? emails : new ArrayList<>(); }

    public boolean isServiceUser(String name, String email) {
        if (name != null) {
            for (String configured : names) {
                if (configured != null && configured.equalsIgnoreCase(name)) return true;
            }
        }
        if (email != null) {
            for (String configured : emails) {
                if (configured != null && configured.equalsIgnoreCase(email)) return true;
            }
        }
        return false;
    }
}
