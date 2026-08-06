package com.example.millisautosend;

interface IShizukuShellService {
    int injectEnter() = 1;
    int getServiceUid() = 2;
    void destroy() = 16777114;
}
