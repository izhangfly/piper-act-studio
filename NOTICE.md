# Notices

PiPER ACT Studio, copyright Ian Zhang (izhangfly), GPL-3.0.

Training and the dataset format are [LeRobot](https://github.com/huggingface/lerobot) (Apache-2.0), used as a
library; the policy is ACT (Zhao et al., "Learning Fine-Grained Bimanual Manipulation with Low-Cost
Hardware", RSS 2023) as implemented in LeRobot.

| Path | Origin | Licence |
|---|---|---|
| `nervus-ipc/` | NervusOS IPC protocol (generated Java classes) | GPL-3.0 |
| `nervus-app-sdk/`, `nervus-packaging/` | NervusOS app SDK and Gradle packaging plugin (NervusOS team) | No licence file upstream; also public in izhangfly/NervusOS-Cambridge |

Runtime dependencies are fetched by pip and Gradle under their own licences (LeRobot Apache-2.0, PyTorch
BSD-3-Clause, OpenCV Apache-2.0, PyAV BSD-3-Clause, torchcodec BSD-3-Clause, Compose Multiplatform
Apache-2.0, piper_sdk MIT, python-can LGPL-3.0).
