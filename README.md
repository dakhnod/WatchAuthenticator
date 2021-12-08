# FossilHRAuthenticator
Create a shared secret for Fossil HR watches and more.

## how to use
This app allows authenticating against all endpoints that support the same protocol.
I, for instance, use the authenticator to authenticate my Fossil HR against the Fossil servers.

First, the proper crypto lib needs to be injected into the project. 
The proper lib can be acquired from already existing Apks.
For educational purposes, you can extract the libs from an app like "Fossil smartwatches" v4.6.0.
Just unzip the .apk and extract the "libs/" content, and pack it into the "jniLibs" folder from this project.

<img src="https://user-images.githubusercontent.com/26143255/107892793-af46ea00-6f27-11eb-8c82-045e58be71e2.png" width="300" />

The, after compiling and installing, enter the proper endpoints and credentials into the app.
Once again, for educational purposes, I used Fossils servers.
If authentication succeeds, the app saves the refresh token and reuses it on next open.
(In the screenshot, all the urls start with "https://c.fossil.com", although it is cut off in the screenshot.

<img src="https://user-images.githubusercontent.com/26143255/145288512-c38fa91b-7d1d-4dec-9c51-3aef34867b10.png" width="300" />

Clicking on a scan result should result in a successfull Key negotiaion and the key in the clipboard,
ready to be pasted in apps like GB.
