import os
import subprocess
import shutil

# Locate Maven
mvn_path = None
for path in os.environ.get("PATH", "").split(os.pathsep):
    candidate = os.path.join(path, "mvn.cmd")
    if os.path.exists(candidate):
        mvn_path = candidate
        break
    candidate = os.path.join(path, "mvn")
    if os.path.exists(candidate):
        mvn_path = candidate
        break

if not mvn_path:
    common_dirs = [
        r"C:\Users\Arthur\AppData\Local\Temp\maven-extract",
        r"C:\Program Files",
        os.path.expanduser("~")
    ]
    for d in common_dirs:
        for root, dirs, files in os.walk(d):
            if "mvn.cmd" in files:
                mvn_path = os.path.join(root, "mvn.cmd")
                break
            if "mvn" in files:
                mvn_path = os.path.join(root, "mvn")
                break
        if mvn_path:
            break

print("Maven executable:", mvn_path)
if not mvn_path:
    print("Error: Maven not found.")
    exit(1)

project_dir = r"c:\Users\Arthur\Desktop\WildTimber"
target_dir = os.path.join(project_dir, "target")
plugins_dir = r"C:\Users\Arthur\Desktop\Serveur de test\plugins"

# Clean target first
if os.path.exists(target_dir):
    print("Cleaning target directory...")
    shutil.rmtree(target_dir)

# 1. Compile Paper version
print("\n--- Compiling Paper Profile ---")
cmd_paper = [mvn_path, "clean", "package", "-P", "paper-1.21.11"]
res = subprocess.run(cmd_paper, cwd=project_dir, capture_output=True, text=True)
print(res.stdout)
if res.returncode != 0:
    print("Paper compilation failed!")
    print(res.stderr)
    exit(1)

# 2. Compile Purpur version (no clean so we keep target)
print("\n--- Compiling Purpur Profile ---")
cmd_purpur = [mvn_path, "package", "-P", "purpur-26.1.2"]
res = subprocess.run(cmd_purpur, cwd=project_dir, capture_output=True, text=True)
print(res.stdout)
if res.returncode != 0:
    print("Purpur compilation failed!")
    print(res.stderr)
    exit(1)

# List output files
print("\n--- Output Jars in target/ ---")
for f in os.listdir(target_dir):
    if f.endswith(".jar"):
        filepath = os.path.join(target_dir, f)
        print(f"{f:40} | Size: {os.path.getsize(filepath):10} bytes")
        
        # Copy to test server plugins folder
        dest = os.path.join(plugins_dir, f)
        print(f"Copying to {dest}...")
        shutil.copy2(filepath, dest)

print("\nBuild and copy complete!")
