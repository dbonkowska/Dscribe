package io.github.dbonkowska.dscribe.labs.s01e02;

/**
 * What the run submits. Doubles as the schema of the terminal tool's arguments, so the model is
 * bound to produce exactly these fields — the shape the hub expects is the shape the model fills.
 */
public record Answer(String name, String surname, int accessLevel, String powerPlant) {}