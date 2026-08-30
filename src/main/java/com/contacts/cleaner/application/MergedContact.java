package com.contacts.cleaner.application;

import java.util.ArrayList;
import java.util.List;

/** One merged output contact: scalar field values plus deduplicated phone/email/website slots and notes. */
public class MergedContact {
    public String[] scalarValues;
    public String notes = "";
    public List<String> nameVariants = new ArrayList<>();
    public List<String[]> phones = new ArrayList<>();
    public List<String[]> emails = new ArrayList<>();
    public List<String[]> websites = new ArrayList<>();
}
