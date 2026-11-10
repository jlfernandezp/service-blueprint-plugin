# ServiceBlueprint — ProM Plugin

Plugin for ProM 6.13 that transforms XES event logs into Service Blueprints by classifying activities into service layers using an LLM or keyword rules.

## What it does

Process mining tools produce outputs (DFGs, Petri nets) that are hard to read for non-technical people. This plugin takes an XES log and generates a Service Blueprint — a diagram that organises activities into five horizontal layers:

- **Customer** — what the customer does
- **Frontstage** — visible interactions with the service provider
- **Backstage** — internal provider activities not visible to the customer
- **Support** — underlying systems and infrastructure
- **Unclassified** — activities the classifier couldn't assign reliably

The blueprint is rendered inside ProM and can be corrected manually.

## Requirements

- ProM 6.13 or later
- Java 8
- Maven 3.6+ (only if building from source)
- LLM API key (only for AI mode)

## Installation

### Pre-built JAR

No binary is included in this anonymised artefact; build `service-blueprint-1.0.1.jar` from source (see "Build from source" below).

1. Copy `service-blueprint-1.0.1.jar`
2. Copy it to both `dist/` and `lib/` inside your ProM folder
3. Add this to `packages/packages.xml` under `<installed-packages>`:

<package name="ServiceBlueprint" version="1.0.0" os="all" url=""
         desc="Service Blueprint plugin for ProM"
         hasPlugins="true" license="L-GPL">
  <dependency name="ProM-Framework" />
  <dependency name="ProM-Contexts" />
  <dependency name="XESLite" />
</package>

3. Launch ProM and look for **"Discover Service Blueprint"** in the Actions panel

### Build from source

Clone the repo and copy the ProM JARs from your local installation into `lib/`:

cd tool

cp /path/to/ProM613/lib/OpenXES.jar lib/
cp /path/to/ProM613/lib/ProM-Framework.jar lib/
cp /path/to/ProM613/lib/ProM-Contexts.jar lib/

mvn package

Then follow the JAR installation steps above.

## API key setup

Only needed for AI mode. Create a `.env` file in the root of your ProM folder:

LLM_API_KEY=your-key-here

Or set it as an environment variable before launching ProM:

export LLM_API_KEY=your-key-here   # Linux/macOS
set LLM_API_KEY=your-key-here      # Windows  

## How to use it

1. Import an XES log in ProM
2. Search for `Discover Service Blueprint` and run it
3. Pick a classification mode:

| Mode | Description |
|------|-------------|
| AI | Uses an LLM to classify activities. Best results, needs API key. |
| Auto | Picks XES fields automatically and applies keyword rules. |
| Manual (JSON) | Load your own classification config from a JSON file. |
| Default | Keyword rules with no configuration needed. |

4. The blueprint opens in the visualizer. Low-confidence assignments are highlighted with an orange border — those are the ones worth checking manually.

## Project structure

src/main/java/org/processmining/serviceblueprint/

  classifier/
    AutoFieldSelector.java
    ConfigParser.java
    KeywordClassifier.java
    LLMClassifier.java

  metrics/
    BlueprintMetrics.java

  model/
    ServiceBlueprint.java
    BlueprintActivity.java
    BlueprintLevel.java
    (+ a few more supporting classes)

  plugin/
    ServiceBlueprintDiscoveryPlugin.java
    ServiceBlueprintVisualizerPlugin.java

  visualization/
    BlueprintPanel.java
