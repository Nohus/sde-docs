#!/usr/bin/env python3

from os.path import join, dirname, abspath, splitext
from os import walk

#
# Configuration
#
# The mapping of file extensions to language names. The key is the file extension
# (including the dot), and the value is the language name.
# The name will be used in the generated markdown file.
EXTENSION_MAPPING = {
    ".py": "Python",
    ".cs": "C#",
    ".kt": "Kotlin",
    ".java": "Java",
    ".go": "Go",
    ".php": "PHP",
    ".ts": "TypeScript",
    ".schema.json": "JSON Schema"
}

# The mapping of file extensions to syntax highlighting names. The key is the file
# extension (including the dot), and the value is the syntax highlighting name.
SYNTAX_MAPPING = {
    ".py": "python",
    ".cs": "csharp",
    ".kt": "kotlin",
    ".java": "java",
    ".go": "go",
    ".php": "php",
    ".ts": "typescript",
    ".schema.json": "json"
}

# The file extension for the combined markdown file.
COMBINED_EXT = ".md"

#
# End of configuration
#

# We sort the languages by their name to ensure a consistent order in the generated file.
LANGUAGE_ORDER = sorted(EXTENSION_MAPPING.items(), key=lambda x: x[1])

# Precompute extensions sorted by length (desc) to support multi-part extensions like ".schema.json".
SORTED_EXTENSIONS = sorted(EXTENSION_MAPPING.keys(), key=len, reverse=True)

# The path to the snippets folder.
snipets_path = abspath(join(dirname(abspath(__file__)), "..", "snippets"))

# We could use pathlib/relpath, but this is simpler, and since we're not dealing
# with user input, it's safe enough.
def path_to_snippet(fname):
    if not fname.startswith(snipets_path):
        return None
    return f"snippets/{fname[len(snipets_path) + 1:]}"


def write_if_changed(fname, content):
    try:
        with open(fname, "r") as f:
            old_content = f.read()
    except FileNotFoundError:
        old_content = None

    if old_content != content:
        with open(fname, "w") as f:
            f.write(content)


# Process a folder and generate combined markdown files based on the snippets found.
def process_folder(path, files):
    found = {}
    for fname in files:
        # Determine the longest matching extension from our mapping (supports multi-part extensions)
        matched_ext = None
        for ext in SORTED_EXTENSIONS:
            if fname.endswith(ext):
                matched_ext = ext
                break
        if not matched_ext:
            continue
        base = fname[: -len(matched_ext)]
        found.setdefault(base, []).append(matched_ext)
    for base, exts in found.items():
        fname = join(path, f"{base}{COMBINED_EXT}")
        content = []
        for ext, lang in LANGUAGE_ORDER:
            if ext in exts:
                infile = join(path, f"{base}{ext}")
                content.extend(
                    [
                        f'=== "{lang}"',
                        "",
                        f"    ```{SYNTAX_MAPPING[ext]}",
                        f'    --8<-- "{path_to_snippet(infile)}"',
                        f"    ```",
                    ]
                )
        content.append("")
        write_if_changed(fname, "\n".join(content))


def generate():
    for root, _, files in walk(snipets_path):
        process_folder(abspath(join(snipets_path, root)), files)


def on_pre_build(**kwargs):
    generate()
