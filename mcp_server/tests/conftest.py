import sys
from pathlib import Path

# The MCP server is a plain script rather than an installed package; tests import
# it directly from the parent directory.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
