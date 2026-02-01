#!/bin/bash
# Create new Kotlin project with Gradle

PROJECT_NAME="${1:-my-kotlin-project}"

if [ -d "$PROJECT_NAME" ]; then
    echo "Error: Directory $PROJECT_NAME already exists"
    exit 1
fi

echo "Creating Kotlin project: $PROJECT_NAME"
cp -r /home/jovyan/templates/kotlin-project "$PROJECT_NAME"
cd "$PROJECT_NAME"

# Update project name in settings.gradle.kts
sed -i "s/kotlin-project/$PROJECT_NAME/g" settings.gradle.kts

echo "✓ Project created: $PROJECT_NAME"
echo ""
echo "Next steps:"
echo "  cd $PROJECT_NAME"
echo "  gradle build        # Build the project"
echo "  gradle run          # Run the application"
echo "  gradle test         # Run tests"
