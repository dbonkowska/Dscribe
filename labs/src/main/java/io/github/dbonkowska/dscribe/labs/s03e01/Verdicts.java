package io.github.dbonkowska.dscribe.labs.s03e01;

import java.util.List;

record Verdicts(List<Verdict> verdicts) {
    record Verdict(int index, String stance) {}
}
