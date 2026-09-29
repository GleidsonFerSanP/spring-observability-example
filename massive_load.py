import urllib.request
import json
import random
import time
from concurrent.futures import ThreadPoolExecutor

url_post = 'http://localhost:8080/api/v1/orchestrator/users'
url_get = 'http://localhost:8080/api/v1/orchestrator/users/'
headers = {'Content-Type': 'application/json'}

user_pool = ['user' + str(i) for i in range(1, 21)] + ['slow', 'error', 'flake']

def send_get():
    user = random.choice(user_pool)
    req = urllib.request.Request(url_get + user, headers=headers, method='GET')
    try:
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass

def send_post():
    user = random.choice(user_pool)
    data = json.dumps({'userId': user, 'email': f'{user}@example.com'}).encode('utf-8')
    req = urllib.request.Request(url_post, data=data, headers=headers, method='POST')
    try:
        urllib.request.urlopen(req, timeout=2)
    except Exception:
        pass

if __name__ == "__main__":
    print("Carga continua rodando em background (GET sync + POST async)...")
    while True:
        with ThreadPoolExecutor(max_workers=8) as executor:
            # 6 GETs (sync E2E e disjuntores)
            for _ in range(6):
                executor.submit(send_get)
            # 4 POSTs (async Kafka e SQS)
            for _ in range(4):
                executor.submit(send_post)
        time.sleep(1.5)
