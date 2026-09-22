#!/bin/bash
touch test_file.pdf
curl -X POST http://localhost:9090/v1/documents/upload \
  -H "X-Tenant-Id: 00000000-0000-0000-0000-000000000000" \
  -F "file=@test_file.pdf"
