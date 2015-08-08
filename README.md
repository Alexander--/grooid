What is this project
==========
An Android port of Groovy (specifically a Groovy Shell), capable of running simple scripts.
Groovy is a dynamic programming language for Java platform with optional static mode. It is a superset 
of Java language with improved syntaxis and additional features for scripting, run-time and compile-time 
metaprogramming and creation of rich domain-specific languages.

This application can execute Groovy Scripts from external sources (files, web, Android content 
providers). Those scripts have full direct access to Android API and can acquire additional libraries, 
from Maven, using Groovy Grapes package manager (yes, right on Android device).

A number of extra measures are taken to make things work - namespace separation, wakelocks, multiple 
fixes to Groovy and third-party components, see detailed explanation below.

Trivia
==========
Groovy has gained basic Android support long time ago thanks to hard work of numerous contributors - 
this application itself is written in Groovy (in static compilation mode). Unfortunately one of the main 
features of Groovy - ability to interpret textual scripts on fly - was still missing, the Groovy code 
still had to be compiled using Android SDK and installed on device as distinct app.

Few more things lot [had to be done](https://melix.github.io/blog/2014/06/grooid2.html) to make that one 
work. Cédric's project showcased a good approach, but it was still half-baked: no Grapes, no ability to
`include` other scripts, results of script compilation are thrown away after each run. This project
improves upon that work, both in terms of polish and new features added.

* Grapes (and Apache Ivy, used as backend) had to be adapted for Android
* A specialized classloader had to be written
* A workaround for lack of class unloading in Android had to be implemented

There are still a lot more things to do: better visual feedback, more API helpers for running code in
context of Android components (Services, Activities and Broadcast Receivers). Even points above have to 
be further improved upon before anything useful is produced.

As usual, contributions are welcome.

Building from sources
==========

````bash
git clone --recursive "https://github.com/Alexander--/grooid"
cd grooid
./gradlew assembleDebug
````
Some extra setup may be currently required, this will be fixed soon.

Current state
==========
See TODO and LIMITATIONS in repository root for overview of short term goals and challenges.
