package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.Map;

record Reading(String id, String sensorType, Map<String, Double> values, String notes) {}
