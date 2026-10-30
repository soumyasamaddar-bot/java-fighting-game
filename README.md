# Java-Based Strategic Fighting & Character Collection Game

A Java-based fighting and character-collection game featuring strategic combat, unique fighter abilities, team building, weighted summoning, and character progression.

## About the Project

This project is a turn-based strategic fighting game developed in Java. It combines character collection and progression systems with a combat engine where players build teams of fighters and use different cards, abilities, and tactical switches to defeat opponents.

The project focuses on object-oriented programming, game logic, character-specific mechanics, probability systems, and persistent player progression.

## Key Features

### 1. Strategic Combat System
- Card-based combat with Strike, Blast, Quirk, Special Move, and Ultimate cards.
- Ki and Vanish resource management.
- Combo execution and card-drawing mechanics.
- Character switching with cooldowns.
- Cover changes and defensive interactions.
- Character colour advantages, disadvantages, and immunity mechanics.
- Status effects and temporary combat buffs.

### 2. Character Collection & Summoning
- Standard and themed summon banners.
- Weighted random character selection.
- Featured and Headliner character classifications.
- Pity system with a configured guarantee after 30 unsuccessful summons.
- Duplicate character collection.

### 3. Character Progression
- Seven-copy Limit Break progression.
- Damage, defence, and HP improvements.
- Character strength tiers ranging from W to C.
- Additional Featured Boost system.
- Excess duplicate copies converted into coins.

### 4. Tactical AI Decision-Making
- Health-based fighter switching.
- Playable-card availability evaluation.
- Opponent pressure consideration.
- Tactical selection of available fighters.

### 5. Character-Specific Abilities
Individual fighters can have unique mechanics through inheritance and method overriding, including:
- Transformations and power-state changes.
- Custom gauges and counters.
- Resource manipulation.
- Temporary buffs and debuffs.
- Special combat interactions.

### 6. Save System
- Persistent coin balance.
- Saved summon pity counters.
- Character inventory and duplicate copies.
- Java Properties-based local save storage.

## Technologies & Concepts

- **Programming Language:** Java
- **Programming Concepts:** Object-Oriented Programming, Inheritance, Polymorphism, Method Overriding
- **Algorithms:** Weighted Random Selection, Conditional Decision-Making, Multiplier Calculations
- **Data Persistence:** Java Properties
- **Game Development:** Combat Logic, Resource Management, Character Progression, AI Decision Logic

## Project Structure

```text
java-fighting-game/
├── Main.java
├── README.md
├── .gitignore
└── Java_Game_Project_Portfolio.pdf
```

## Running the Project

1. Install a Java Development Kit (JDK).
2. Clone or download this repository.
3. Open the project in a Java IDE or terminal.
4. Compile and run the application.

For a single-file setup, the terminal commands are:

```bash
javac Main.java
java Main
```

## Project Documentation

For a more detailed explanation of the architecture, combat mechanics, progression systems, and engineering concepts, see:

[Java Game Project Portfolio (PDF)](Java_Game_Project_Portfolio.pdf)

## Future Improvements

- Separate the large source file into smaller classes and packages.
- Add automated testing for combat and balance calculations.
- Expand tactical AI behaviour.
- Improve the user interface.
- Introduce more fighters, banners, and abilities.
- Add more systematic combat balance simulations.

---

**Project:** Java-Based Strategic Fighting & Character Collection Game  
**Repository:** [java-fighting-game](https://github.com/soumyasamaddar-bot/java-fighting-game)
