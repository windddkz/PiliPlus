static int startup_prefill_enabled;
static int replace_startup_prefill(int install){
    if(!startup_prefill_enabled)return 0;
    // shortcut: debug-only 40/80ms comparison; device policy is not established.
    return patch_instruction((uint32_t *)(base+0x18c26c),install?0x9363fd08:0x9362fd08,
        install?0x9362fd08:0x9363fd08,mprotect);
}
