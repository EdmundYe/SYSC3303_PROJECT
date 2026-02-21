Drone Fire Suppression System — Iteration 2A multi-subsystem simulation of an autonomous drone fire suppression system. This iteration builds on Iteration 1 by introducing state machines for the scheduler and drone subsystems, with a single drone coordinating fire response across zones.

Breakdown of Responsibilities
Derrick and Asaad: Coding
Edmund: UML / Sequence diagrams
ChenWei: Testing

Prerequisites
Java JDK 25
JUnit 5
An IDE such as IntelliJ IDEA or Eclipse

To Run the Project
Install Java JDK 25
Clone or download the repository
Open the project in your IDE
Edit the run configuration to use Java 25 and set Main.java as the main class
Run the project

To Run Tests
Install JUnit 5 dependencies in your IDE or build tool
Navigate to the test files in the src directory
Run each test individually or via your IDE's test runner


Project Structure
src/ — Main source code including scheduler, drone subsystem, and GUI components
src/tests/ — JUnit 5 test files
iteration2-class-diagram — UML class diagram reflecting Iteration 2 changes
iteration2-sequence-diagram — Sequence diagram showing scheduler-drone message passing
drone-state-machine-diagram - State machine diagram showing drone states and event triggers
scheduler-state-machine-diagram - State machine diagram showing scheduler states and event triggers
