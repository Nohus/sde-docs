Repository for the [EVE Online SDE Documentation](https://sde.riftforeve.online/) website.

### Creating a virtual environment

This project uses python, so it is recommended to create a virtual environment to manage the dependencies.
This ensures that you don't clutter your system python installation with dependencies that are only needed
for this project.

```bash
python3 -m venv .venv
source .venv/bin/activate
```

When you are starting a new terminal session, you will need to activate the virtual environment again.
You can do this by navigating to the project directory and running the `source .venv/bin/activate` command.

### Installing dependencies

Now that you have a virtual environment set up, you can install the dependencies for this project.
This can be done by running the following command:

```bash
pip install -r requirements.txt
```

### Running the project

Now that you have the dependencies installed, you can run the project locally.
This can be done by running the following command:

```bash
mkdocs serve
```

This will start a local webserver that you can access by navigating to `http://127.0.0.1:8000/docs/` in your browser.

You can close the server by pressing `Ctrl+C` in the terminal.
