package io.github.dbonkowska.dscribe.labs.s01e01;

import org.apache.commons.csv.CSVRecord;

import java.util.List;

public record Person(
        String name,
        String surname,
        String gender,
        int born,
        String city,
        List<String> tags
) {
    static Person from(CSVRecord record) {
        return new Person(
                record.get("name"),
                record.get("surname"),
                record.get("gender"),
                Integer.parseInt(record.get("birthDate").substring(0, 4)),
                record.get("birthPlace"),
                List.of()
        );
    }

    Person withTags(List<String> tags) {
        return new Person(name, surname, gender, born, city, tags);
    }
}