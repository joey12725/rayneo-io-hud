package io.lampyr.glasses.input.shizuku;
import io.lampyr.glasses.input.shizuku.IRawInputCallback;

interface IRawInputService {
    void start(String name, int vendor, int product, IRawInputCallback callback) = 0;
    void destroy() = 16777114;
}
