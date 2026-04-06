Drone Fire Suppression System — Iteration 2A multi-subsystem simulation of an autonomous drone fire suppression system. This iteration builds on Iteration 1 by introducing state machines for the scheduler and drone subsystems, with a single drone coordinating fire response across zones.

Breakdown of Responsibilities
Asaad: Coding
Derrick: Tests and Coding
Edmund: Coding
ChenWei: UML

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
src/ — Main source code including scheduler, drone subsystem, and SubSystems.GUI components
src/tests/ — JUnit 5 test files
iteration5-UML-class-diagram — UML class diagram reflecting Iteration 5 changes
iteration2-sequence-diagram — Sequence diagram showing scheduler-drone message passing
drone_subsystem_state_machine_diagram - State machine diagram showing drone states and event triggers
scheduler_subsystem_state_machine_diagram - State machine diagram showing scheduler states and event triggers

Iteration progress:

Iteration 5:

Assad:

Edmund:

Derrick:

Chenwei:

Iteration 4:
Derrick:
-Added backwards compatibility to some deprecated tests as to not break build
-implemented faults to drones, drone_stuck being a short pause and return to base, and nozzle being an offline return to base unusable

Asaad:
- Added the following tests:
- Unit tests for drone mission execution and state transitions
- Unit tests for scheduler queueing, dispatching, and reassignment logic
- Unit tests for fault injection handling
- Integration tests for main use case flow across all subsystems under normal and faulty conditions

Edmund:
- Implemented dynamic drone scheduling
- revamped GUI to show drone state and fault changes, and drone arrival to middle of zone
- Implemented Drone positioning and tracking

Chenwei:
- Edit CLass Diagram
- Fault type enumeration and made for support fault injection

Iteration 3:
Derrick:
-Added scheduling logic for drone selection: Dispatches drones that are reachable, pick drone with least amount of missions, tie break user drone with lower ID.
-Added SENDING_DRONE state to scheduler
-added new set of test for new udp system
-Deprecated old tests

Asaad:
- Implemented UDP for Fire incident and Drone subsystems
- Updated SubSystems.GUI

Edmund:
- Implemented UDP communication for scheduler subsystem
- Added Drone logic and travel calculations using Iteration 0 values

Chenwei:
- Updated UML CLass Diagram
- Fixed few bugs

Iteration 2:
Derrick:
-implemented SubSystems.GUI to have 4 grids, keeps track of active fires and if drone is busy or not (currently tracked through SystemCounts class)
-implemented scheduler state machine

Asaad:
- implemented drone state machine

Chenwei:
- implemented test files

Edmund:
- Updated UML Class Diagrams by adding states and events
- Created state machine diagrams for both scheduler and drone subsystems
