import requests
import time
import json

print("=== TEST UPLOAD FACTURE ===\n")

# Mesurer le temps
start_time = time.time()

# Upload fichier
url = "http://localhost:8089/api/dynamic-invoices/upload"
files = {'file': open('src/archive/aaa.pdf', 'rb')}

try:
    response = requests.post(url, files=files)
    end_time = time.time()
    
    duration = end_time - start_time
    
    print(f"✅ Status: {response.status_code}")
    print(f"⏱️  Temps total: {duration:.2f} secondes")
    print(f"\n📊 Réponse:")
    
    if response.status_code == 200:
        data = response.json()
        print(json.dumps(data, indent=2, ensure_ascii=False))
        
        # Extraire infos importantes
        if 'invoiceNumber' in data:
            print(f"\n✅ Numéro facture: {data.get('invoiceNumber')}")
        if 'supplier' in data:
            print(f"✅ Fournisseur: {data.get('supplier')}")
        if 'ice' in data:
            print(f"✅ ICE: {data.get('ice')}")
        if 'amountTTC' in data:
            print(f"✅ Montant TTC: {data.get('amountTTC')}")
            
    else:
        print(response.text)
        
except Exception as e:
    print(f"❌ Erreur: {e}")
finally:
    files['file'].close()
