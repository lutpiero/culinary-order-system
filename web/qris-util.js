/**
 * QRIS (Quick Response Code Indonesian Standard) Payment Utility
 * Generates QRIS QR code data for payment transactions
 * 
 * Based on BI (Bank Indonesia) QRIS specification
 */

/**
 * Calculate CRC-16 CCITT checksum for QRIS data
 * @param {string} data - The QRIS data string
 * @returns {string} - 4-character hex CRC value
 */
function getQrisCrc(data) {
  let crc = 0xFFFF;
  
  for (let i = 0; i < data.length; i++) {
    crc ^= (data.charCodeAt(i) << 8);
    
    for (let j = 0; j < 8; j++) {
      if (crc & 0x8000) {
        crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
      } else {
        crc = (crc << 1) & 0xFFFF;
      }
    }
  }
  
  return crc.toString(16).toUpperCase().padStart(4, '0');
}

/**
 * Generate QRIS payment string with amount and reference number
 * @param {number} amount - Transaction amount in Indonesian Rupiah
 * @param {string} partnerRefno - Reference number (max 25 characters, printable ASCII)
 * @returns {string|null} - QRIS payment string, or null if validation fails
 */
function generateQrisWithRefno(amount, partnerRefno) {
  // --- VALIDATION CHECKS ---
  if (!partnerRefno) {
    console.error("Failure: Partner reference cannot be empty.");
    return null;
  }
  
  if (partnerRefno.length > 25) {
    console.error(`Failure: Reference '${partnerRefno}' exceeds 25 characters.`);
    return null;
  }
  
  // Check all characters are printable ASCII (32-126)
  for (let i = 0; i < partnerRefno.length; i++) {
    const charCode = partnerRefno.charCodeAt(i);
    if (charCode < 32 || charCode > 126) {
      console.error("Failure: Reference contains invalid characters.");
      return null;
    }
  }
  // -------------------------

  // Get QRIS configuration (from config.js)
  const qrisConfig = window.QRIS_CONFIG || {
    prefix: "00020101021226710019ID.CO.DSPRATAMA.WWW011893600998000001350302159980260752866590303UMI51440014ID.CO.QRIS.WWW0215ID10265469836970303UMI520486615303360",
    middle: "5802ID5918MASJID NURUL ISLAM6015JAKARTA SELATAN610512720",
    suffix: "6304"
  };

  // 1. Format the Amount TLV (Tag 54)
  const amountStr = String(amount);
  const amountTlv = `54${String(amountStr.length).padStart(2, '0')}${amountStr}`;
  
  // 2. Format Additional Data Field (Tag 62)
  // Reference Label (Sub-tag 05) - This is what usually maps to PARTNER_REFNO
  const subTag05 = `05${String(partnerRefno.length).padStart(2, '0')}${partnerRefno}`;
  
  // Terminal Label (Sub-tag 07) - Required by acquirer
  const subTag07 = "0703A01";
  
  // Combine the subtags and calculate total length for Tag 62
  const combinedSubTags = subTag05 + subTag07;
  const tag62Tlv = `62${String(combinedSubTags.length).padStart(2, '0')}${combinedSubTags}`;
  
  // 3. Split the base QRIS payload to inject the new dynamic blocks
  const prefix = qrisConfig.prefix;
  const middle = qrisConfig.middle;
  const suffix = qrisConfig.suffix;
  
  // 4. Construct the new payload and append the recalculated checksum
  const baseQris = prefix + amountTlv + middle + tag62Tlv + suffix;
  const crc = getQrisCrc(baseQris);
  const finalQris = baseQris + crc;
  
  return finalQris;
}

/**
 * Generate QR code image from QRIS string
 * Uses the QRious library (must be included in HTML)
 * @param {string} qrisString - QRIS payment string
 * @returns {string} - Data URL of the QR code image
 */
function generateQrCodeImage(qrisString) {
  // Use QRious library (must be loaded from CDN)
  if (typeof QRious !== 'undefined') {
    const canvas = document.createElement('canvas');
    const qr = new QRious({
      element: canvas,
      value: qrisString,
      size: 300,
      level: 'M',
      mime: 'image/png'
    });
    return canvas.toDataURL('image/png');
  } else {
    throw new Error('QRious library not loaded. Please ensure CDN script is included in HTML.');
  }
}

/**
 * Generate complete QRIS payment data with QR code
 * @param {number} amount - Transaction amount
 * @param {string} referenceNumber - Order reference (order ID)
 * @returns {Object} - Object with qris string and qr code image
 */
function generateQrisPayment(amount, referenceNumber) {
  const qrisString = generateQrisWithRefno(amount, referenceNumber);
  
  if (!qrisString) {
    return null;
  }
  
  try {
    const qrCodeImage = generateQrCodeImage(qrisString);
    
    return {
      qrisString: qrisString,
      qrCodeImage: qrCodeImage,
      amount: amount,
      referenceNumber: referenceNumber
    };
  } catch (error) {
    console.error("Error generating QR code:", error);
    return null;
  }
}

// Export for use in module systems
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    getQrisCrc,
    generateQrisWithRefno,
    generateQrCodeImage,
    generateQrisPayment
  };
}
