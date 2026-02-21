# Python & JupyterHub Code Generation Patterns

## Python Best Practices for Data Analysis

### Imports Organization
```python
# Standard library
import os
import sys
from datetime import datetime

# Third-party
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

# Local
from my_module import my_function
```

### Common Data Analysis Functions

#### Calculate Mean
```python
def calculate_mean(numbers: list[float]) -> float:
    """Calculate the arithmetic mean of a list of numbers."""
    if not numbers:
        raise ValueError("Cannot calculate mean of empty list")
    return sum(numbers) / len(numbers)
```

#### Load and Preview CSV
```python
import pandas as pd

def load_and_preview_csv(filepath: str, rows: int = 5) -> pd.DataFrame:
    """Load CSV file and display first N rows."""
    df = pd.read_csv(filepath)
    print(f"Shape: {df.shape}")
    print(f"\nFirst {rows} rows:")
    print(df.head(rows))
    return df
```

### Pandas Common Operations

#### Data Loading
```python
# CSV
df = pd.read_csv('data.csv')

# JSON
df = pd.read_json('data.json')

# SQL
import psycopg2
from sqlalchemy import create_engine

engine = create_engine('postgresql://user:pass@host:5432/db')
df = pd.read_sql('SELECT * FROM table', engine)
```

#### Data Exploration
```python
# Basic info
df.info()
df.describe()
df.head()

# Column operations
df['column'].value_counts()
df['column'].unique()
df['column'].nunique()

# Missing data
df.isnull().sum()
df.dropna()
df.fillna(value)
```

#### Data Filtering
```python
# Boolean indexing
df[df['age'] > 30]
df[(df['age'] > 30) & (df['city'] == 'NYC')]

# Query method
df.query('age > 30 and city == "NYC"')

# isin
df[df['status'].isin(['active', 'pending'])]
```

#### GroupBy Operations
```python
# Group and aggregate
df.groupby('category')['value'].mean()
df.groupby('category').agg({'value': ['mean', 'sum', 'count']})

# Multiple columns
df.groupby(['category', 'region'])['sales'].sum()
```

### Visualization with Matplotlib

#### Line Plot
```python
import matplotlib.pyplot as plt

def create_line_plot(x, y, title='', xlabel='', ylabel=''):
    """Create a simple line plot."""
    plt.figure(figsize=(10, 6))
    plt.plot(x, y, marker='o', linestyle='-', linewidth=2)
    plt.title(title)
    plt.xlabel(xlabel)
    plt.ylabel(ylabel)
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.show()
```

#### Bar Chart
```python
def create_bar_chart(categories, values, title=''):
    """Create a bar chart."""
    plt.figure(figsize=(10, 6))
    plt.bar(categories, values, color='steelblue')
    plt.title(title)
    plt.xlabel('Categories')
    plt.ylabel('Values')
    plt.xticks(rotation=45, ha='right')
    plt.tight_layout()
    plt.show()
```

#### Scatter Plot
```python
def create_scatter_plot(x, y, title='', xlabel='', ylabel=''):
    """Create a scatter plot."""
    plt.figure(figsize=(10, 6))
    plt.scatter(x, y, alpha=0.6, s=50)
    plt.title(title)
    plt.xlabel(xlabel)
    plt.ylabel(ylabel)
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.show()
```

#### Histogram
```python
def create_histogram(data, bins=30, title=''):
    """Create a histogram."""
    plt.figure(figsize=(10, 6))
    plt.hist(data, bins=bins, color='skyblue', edgecolor='black')
    plt.title(title)
    plt.xlabel('Value')
    plt.ylabel('Frequency')
    plt.grid(True, alpha=0.3, axis='y')
    plt.tight_layout()
    plt.show()
```

### NumPy Operations

#### Array Creation
```python
import numpy as np

# From list
arr = np.array([1, 2, 3, 4, 5])

# Zeros and ones
zeros = np.zeros((3, 4))
ones = np.ones((2, 3))

# Range
arr = np.arange(0, 10, 2)  # [0, 2, 4, 6, 8]
arr = np.linspace(0, 1, 5)  # [0, 0.25, 0.5, 0.75, 1.0]
```

#### Statistical Operations
```python
# Mean, median, std
data = np.array([1, 2, 3, 4, 5])
mean = np.mean(data)
median = np.median(data)
std = np.std(data)

# Min, max
min_val = np.min(data)
max_val = np.max(data)

# Percentiles
p25 = np.percentile(data, 25)
p75 = np.percentile(data, 75)
```

### HTTP Requests

#### Using requests Library
```python
import requests

# GET request
response = requests.get('https://api.example.com/data')
if response.status_code == 200:
    data = response.json()
    print(data)
else:
    print(f"Error: {response.status_code}")

# POST request
payload = {'key': 'value'}
response = requests.post('https://api.example.com/endpoint', json=payload)

# With headers
headers = {'Authorization': 'Bearer token'}
response = requests.get('https://api.example.com/data', headers=headers)
```

#### Using httpx (async)
```python
import httpx
import asyncio

async def fetch_data(url: str):
    """Async HTTP GET request."""
    async with httpx.AsyncClient() as client:
        response = await client.get(url)
        return response.json()

# Usage
data = asyncio.run(fetch_data('https://api.example.com/data'))
```

### JupyterHub Specific Patterns

#### Environment Detection
```python
def is_jupyter():
    """Check if code is running in Jupyter."""
    try:
        from IPython import get_ipython
        if 'IPKernelApp' in get_ipython().config:
            return True
    except:
        pass
    return False

if is_jupyter():
    from tqdm.notebook import tqdm
else:
    from tqdm import tqdm
```

#### Display Rich Output
```python
from IPython.display import display, HTML, Markdown

# Display dataframe
display(df.head())

# Display HTML
display(HTML('<h2>Results</h2>'))

# Display Markdown
display(Markdown('## Analysis Results\n- Point 1\n- Point 2'))
```

#### Interactive Widgets
```python
import ipywidgets as widgets
from IPython.display import display

# Slider
slider = widgets.IntSlider(value=50, min=0, max=100, description='Value:')
display(slider)

# Dropdown
dropdown = widgets.Dropdown(
    options=['Option A', 'Option B', 'Option C'],
    value='Option A',
    description='Select:'
)
display(dropdown)
```

### Common Data Science Workflows

#### Load, Clean, Analyze
```python
import pandas as pd
import numpy as np

# Load data
df = pd.read_csv('data.csv')

# Clean data
df = df.dropna()  # Remove missing values
df['date'] = pd.to_datetime(df['date'])  # Convert dates
df = df[df['value'] > 0]  # Filter invalid values

# Analyze
summary = df.groupby('category')['value'].agg(['mean', 'sum', 'count'])
print(summary)

# Visualize
summary['mean'].plot(kind='bar', title='Average Value by Category')
```

#### Time Series Analysis
```python
import pandas as pd
import matplotlib.pyplot as plt

# Load with datetime index
df = pd.read_csv('timeseries.csv', parse_dates=['date'], index_col='date')

# Resample to daily frequency
daily = df.resample('D').mean()

# Rolling average
df['rolling_7d'] = df['value'].rolling(window=7).mean()

# Plot
plt.figure(figsize=(12, 6))
plt.plot(df.index, df['value'], label='Original', alpha=0.5)
plt.plot(df.index, df['rolling_7d'], label='7-day average', linewidth=2)
plt.legend()
plt.title('Time Series with Rolling Average')
plt.show()
```

### Error Handling Best Practices

```python
def safe_divide(a: float, b: float) -> float:
    """Safely divide two numbers."""
    try:
        result = a / b
        return result
    except ZeroDivisionError:
        print("Error: Division by zero")
        return float('inf')
    except TypeError:
        print("Error: Invalid types for division")
        return None

def safe_file_read(filepath: str) -> str:
    """Safely read file contents."""
    try:
        with open(filepath, 'r') as f:
            return f.read()
    except FileNotFoundError:
        print(f"Error: File not found: {filepath}")
        return ""
    except PermissionError:
        print(f"Error: Permission denied: {filepath}")
        return ""
```

### Library Recommendations by Task

- **Data manipulation**: pandas, numpy
- **Visualization**: matplotlib, seaborn, plotly
- **Machine Learning**: scikit-learn, tensorflow, pytorch
- **HTTP requests**: requests, httpx
- **Async operations**: asyncio, aiohttp
- **Database**: psycopg2, sqlalchemy, pymongo
- **Testing**: pytest, unittest
- **Type checking**: mypy, pydantic
