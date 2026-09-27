package io.github.dbonkowska.dscribe.labs.s04e04;

import io.github.dbonkowska.dscribe.schema.SchemaUtils;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The model's whole answer: only what takes reading prose. Every place it names is one of the
 * trade log's, and every good it gives a singular for is one the trade log sells; paths, links and
 * spelling are code's.
 *
 * @param cities what each place needs
 * @param people who handles trade where
 * @param goods  the nominative singular of each good as the trade log writes it
 */
record Extraction(List<CityNeeds> cities, List<Person> people, List<GoodForm> goods) {

    record CityNeeds(String city, List<Need> needs) {}

    /** @param good the nominative singular */
    record Need(String good, int quantity) {}

    /** @param fullName first name and surname, joined from wherever the notes give them */
    record Person(String fullName, String city) {}

    record GoodForm(String asWritten, String singular) {}

    /** Places and trade log goods narrowed to what the trade log holds, so neither can be invented. */
    static ObjectNode schema(List<String> cities, List<String> tradedGoods) {
        ObjectNode schema = SchemaUtils.from(Extraction.class);
        allow(schema, "/properties/cities/items/properties/city", cities);
        allow(schema, "/properties/people/items/properties/city", cities);
        allow(schema, "/properties/goods/items/properties/asWritten", tradedGoods);
        return schema;
    }

    private static void allow(ObjectNode schema, String pointer, List<String> values) {
        ArrayNode allowed = SchemaUtils.at(schema, pointer).putArray("enum");
        values.forEach(allowed::add);
    }

    /**
     * What the schema cannot hold: a place or a good given twice, a trade log good left without a
     * singular, a count that is not a count. Returned rather than thrown, so the run can report
     * these with the tree's own problems in one refusal.
     *
     * <p>A place left out of the answer altogether is not one of them — the notes do not promise
     * that every place in the trade log needs something. It gets no file, and the tree's check
     * catches that only when something links to it: a place with a person or a good for sale. A
     * place with neither, left out, is not refused anywhere.
     */
    List<String> problems(List<String> tradedGoods) {
        List<String> problems = new ArrayList<>();

        Set<String> seen = new HashSet<>();
        for (CityNeeds city : cities) {
            if (!seen.add(city.city())) {
                problems.add("needs are given twice for " + city.city());
            }
            if (city.needs().isEmpty()) {
                problems.add(city.city() + " needs nothing");
            }
            Set<String> goods = new HashSet<>();
            for (Need need : city.needs()) {
                if (!goods.add(Fold.name(need.good()))) {
                    problems.add(city.city() + " needs " + need.good() + " twice");
                }
                if (need.quantity() <= 0) {
                    problems.add(city.city() + " needs " + need.quantity() + " of " + need.good());
                }
            }
        }
        Set<String> forms = new HashSet<>();
        for (GoodForm form : goods) {
            if (!forms.add(form.asWritten())) {
                problems.add("two singulars are given for " + form.asWritten());
            }
            if (Fold.name(form.singular()).isEmpty()) {
                problems.add("the singular of " + form.asWritten() + " is blank");
            }
        }
        tradedGoods.stream()
                .filter(good -> !forms.contains(good))
                .forEach(good -> problems.add("no singular is given for " + good));

        Set<String> names = new HashSet<>();
        for (Person person : people) {
            if (!names.add(Fold.name(person.fullName()))) {
                problems.add(person.fullName() + " is listed twice");
            }
        }
        return problems;
    }
}
