# MaruAi 

A High-Performance On-Device Anime Image Upscaler Application for Android powered by RRDBNet Architecture.

## 🌟 Overview
**MaruAi** is an Android application designed for **4x anime image upscaling (x4)**. By implementing on-device deep learning inference, the application ensures high-speed processing, low latency, and absolute user data privacy without relying on external cloud servers.

## 🛠️ Technical Stack
* **AI Architecture:** Utilizes the advanced **RRDBNet (Residual in Residual Dense Block)** architecture, significantly preserving sharp edges, line art, and vibrant colors specifically optimized for anime illustrations.
* **Core Engine:** The core AI inference engine is implemented in **C++ via Android NDK**, maximizing hardware acceleration (GPU/NPU) to handle heavy neural network computations efficiently.
* **UI & Frontend:** Developed primarily in **Kotlin** for a modern, responsive user experience and robust Android lifecycle management.
* **CI/CD:** Configured with **GitHub Actions** for automated building, integration, and deployment pipelines.


---

## 📱 Screenshots

| Main Screen | Settings Screen |
| :---: | :---: |
| <img src="YOUR_IMAGE_URL_1" width="240" /> | <img src="YOUR_IMAGE_URL_2" width="240" /> |

---

## 🚀 Quick Start
1. Clone the repository:
   ```bash
   git clone https://github.com/icecat8-lab/MaruAi
   ```
2. Open the project in the latest version of **Android Studio**.
3. Ensure **Android NDK** and **CMake** are installed via the SDK Manager.
4. Sync the Gradle project and click **Run** to deploy to an emulator or physical device.
