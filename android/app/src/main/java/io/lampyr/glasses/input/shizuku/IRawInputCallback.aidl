package io.lampyr.glasses.input.shizuku;

oneway interface IRawInputCallback {
    void event(String node, int type, int code, int value, long timeMs);
    void nodes(String name, in String[] paths, boolean exclusive, String detail);
    void lost(String node);
}
