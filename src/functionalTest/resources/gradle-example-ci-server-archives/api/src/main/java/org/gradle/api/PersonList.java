package org.gradle.api;

import org.gradle.apiImpl.Impl;
import org.gradle.shared.Person;

import java.util.ArrayList;


public class PersonList {
    private ArrayList<Person> persons = new ArrayList<Person>();

    public void doSomethingWithImpl() {
        org.apache.commons.lang3.builder.ToStringBuilder stringBuilder;
        try {
            Class.forName("org.apache.commons.io.FileUtils");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        new Impl().implMethod();
    }

}
