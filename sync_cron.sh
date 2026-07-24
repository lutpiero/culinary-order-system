#!/bin/bash
cd /home/lutpiero/marketplace-integrator
.venv/bin/python main.py sync orders --live >> logs/cron.log 2>&1
