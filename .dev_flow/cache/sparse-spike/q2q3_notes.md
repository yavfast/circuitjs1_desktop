Q2/Q3 notes. NW.js 0.64.1 = Chromium 101.0.4951.67, Node 18.0.0 (nwjs.io/blog/v0.64.1/)
WebGPU: origin trial M94-101, flag --enable-unsafe-webgpu; shipped Chrome 113 (Win D3D12, macOS, ChromeOS); Linux later (Intel Gen12+ in 144).
WGSL: no f64 (only shader-f16 ext); gpuweb#2805 open.
mapAsync readback 5-15 ms (gpuweb#4432, #3595).
GLU: 19.56x vs KLU on UF circuit matrices; GPU wins at >200 Mflop factorization; GPU is factorization only.
KLU: LGPL-2.1-or-later. localhost TCP RTT ~334us, UDS ~130us (Node).
Code: 331 java files; 131 import com.google.gwt; element/ 31 of 154; CircuitSimulator 1 (Window); CircuitMath 0; 30 files with native JSNI.
