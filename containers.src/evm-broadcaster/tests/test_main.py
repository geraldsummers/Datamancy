"""
Unit tests for EVM Broadcaster Worker
"""
import pytest
import sys
from pathlib import Path
from unittest.mock import Mock, patch, MagicMock
from decimal import Decimal

# Add app to path
sys.path.insert(0, str(Path(__file__).parent.parent / "app"))

import main


class TestDeriveEvmAddress:
    """Test EVM address derivation"""

    def test_derive_address_with_prefix(self):
        """Test address derivation with 0x prefix"""
        private_key = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        address = main.derive_evm_address(private_key)
        assert address.startswith("0x")
        assert len(address) == 42

    def test_derive_address_without_prefix(self):
        """Test address derivation without 0x prefix"""
        private_key = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        address = main.derive_evm_address(private_key)
        assert address.startswith("0x")
        assert len(address) == 42

    def test_derive_address_consistency(self):
        """Test that same private key produces same address"""
        private_key = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        address1 = main.derive_evm_address(private_key)
        address2 = main.derive_evm_address(private_key)
        assert address1 == address2


class TestBuildTransaction:
    """Test transaction building"""

    @patch('main.Web3')
    def test_build_eth_transaction(self, mock_web3_class):
        """Test building ETH transaction"""
        # Setup mock
        mock_web3 = Mock()
        mock_web3.eth.gas_price = 1000000000  # 1 gwei
        mock_web3.to_wei.return_value = 1000000000000000000  # 1 ETH in wei
        mock_web3_class.return_value = mock_web3

        tx = main.build_transaction(
            chain='base',
            from_addr='0xFrom',
            to_addr='0xTo',
            amount='1.0',
            token='ETH',
            nonce=5
        )

        assert tx['from'] == '0xFrom'
        assert tx['to'] == '0xTo'
        assert tx['nonce'] == 5
        assert tx['chainId'] == 8453  # Base chain ID
        assert tx['gas'] == 21000

    @patch('main.Web3')
    def test_build_usdc_transaction(self, mock_web3_class):
        """Test building USDC transaction"""
        # Setup mocks
        mock_web3 = Mock()
        mock_web3.eth.gas_price = 1000000000
        mock_contract = Mock()
        mock_contract.functions.decimals().call.return_value = 6
        mock_contract.encode_abi.return_value = b'0xencoded'
        mock_web3.eth.contract.return_value = mock_contract
        mock_web3_class.return_value = mock_web3

        tx = main.build_transaction(
            chain='base',
            from_addr='0xFrom',
            to_addr='0xTo',
            amount='100.0',
            token='USDC',
            nonce=10
        )

        assert tx['from'] == '0xFrom'
        assert tx['nonce'] == 10
        assert tx['chainId'] == 8453
        assert tx['to'] == main.CHAINS['base']['usdc']
        assert tx['gas'] == 100000

    def test_unsupported_token(self):
        """Test error on unsupported token"""
        with patch('main.Web3') as mock_web3_class:
            mock_web3 = Mock()
            mock_web3.eth.gas_price = 1000000000
            mock_web3_class.return_value = mock_web3

            with pytest.raises(ValueError, match="not supported"):
                main.build_transaction(
                    chain='base',
                    from_addr='0xFrom',
                    to_addr='0xTo',
                    amount='1.0',
                    token='INVALID',
                    nonce=0
                )


class TestSignTransaction:
    """Test transaction signing"""

    def test_sign_transaction_with_prefix(self):
        """Test signing with 0x prefix"""
        private_key = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        # The from address must match the private key's address
        tx = {
            'from': '0x1Be31A94361a391bBaFB2a4CCd704F57dc04d4bb',
            'to': '0xRecipient',
            'value': 1000000000000000000,
            'gas': 21000,
            'gasPrice': 1000000000,
            'nonce': 0,
            'chainId': 8453
        }
        signed = main.sign_transaction(tx, private_key)
        assert signed.startswith("0x")

    def test_sign_transaction_without_prefix(self):
        """Test signing without 0x prefix"""
        private_key = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
        # The from address must match the private key's address
        tx = {
            'from': '0x1Be31A94361a391bBaFB2a4CCd704F57dc04d4bb',
            'to': '0xRecipient',
            'value': 1000000000000000000,
            'gas': 21000,
            'gasPrice': 1000000000,
            'nonce': 0,
            'chainId': 8453
        }
        signed = main.sign_transaction(tx, private_key)
        assert signed.startswith("0x")


class TestFlaskEndpoints:
    """Test Flask API endpoints"""

    @pytest.fixture
    def client(self):
        """Create test client"""
        main.app.config['TESTING'] = True
        with main.app.test_client() as client:
            yield client

    def test_health_endpoint(self, client):
        """Test health check endpoint"""
        with patch('main.get_db_connection') as mock_db:
            mock_conn = Mock()
            mock_db.return_value = mock_conn

            response = client.get('/health')
            assert response.status_code == 200
            data = response.get_json()
            assert data['service'] == 'evm-broadcaster'
            assert 'chains' in data

    def test_chains_endpoint(self, client):
        """Test chains listing endpoint"""
        response = client.get('/chains')
        assert response.status_code == 200
        data = response.get_json()
        assert 'chains' in data
        assert 'base' in data['chains']
        assert 'arbitrum' in data['chains']
        assert 'optimism' in data['chains']

    def test_tokens_endpoint(self, client):
        """Test tokens listing endpoint"""
        response = client.get('/tokens')
        assert response.status_code == 200
        data = response.get_json()
        assert 'tokens' in data
        assert 'ETH' in data['tokens']
        assert 'USDC' in data['tokens']
        assert 'USDT' in data['tokens']

    def test_submit_missing_key(self, client):
        """Test submit without private key"""
        response = client.post('/submit', json={
            'username': 'testuser',
            'toAddress': '0xRecipient',
            'amount': '1.0',
            'token': 'ETH',
            'chain': 'base'
        })
        assert response.status_code == 400
        data = response.get_json()
        assert 'evmPrivateKey' in data['error']

    def test_submit_unsupported_chain(self, client):
        """Test submit with unsupported chain"""
        response = client.post('/submit', json={
            'username': 'testuser',
            'toAddress': '0xRecipient',
            'amount': '1.0',
            'token': 'ETH',
            'chain': 'invalid_chain',
            'evmPrivateKey': '0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef'
        })
        assert response.status_code == 400
        data = response.get_json()
        assert 'Unsupported chain' in data['error']

    @patch('main.broadcast_tx')
    @patch('main.sign_transaction')
    @patch('main.build_transaction')
    @patch('main.allocate_nonce')
    @patch('main.track_pending')
    def test_submit_success(self, mock_track, mock_nonce, mock_build, mock_sign, mock_broadcast, client):
        """Test successful transaction submission"""
        # Setup mocks
        mock_nonce.return_value = 5
        mock_build.return_value = {
            'from': '0xFrom',
            'to': '0xTo',
            'value': 1000000000000000000,
            'gas': 21000,
            'gasPrice': 1000000000,
            'nonce': 5,
            'chainId': 8453
        }
        mock_sign.return_value = "0xsigned"
        mock_broadcast.return_value = "0xtxhash"

        response = client.post('/submit', json={
            'username': 'testuser',
            'toAddress': '0xRecipient',
            'amount': '1.0',
            'token': 'ETH',
            'chain': 'base',
            'evmPrivateKey': '0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['txHash'] == "0xtxhash"
        assert data['nonce'] == 5
        assert data['status'] == 'submitted'

    @patch('main.Web3')
    def test_status_pending(self, mock_web3_class, client):
        """Test status endpoint for pending transaction"""
        mock_web3 = Mock()
        mock_web3.eth.get_transaction_receipt.side_effect = Exception("Not found")
        mock_web3_class.return_value = mock_web3

        response = client.get('/status/0xtxhash?chain=base')
        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'pending'

    @patch('main.Web3')
    def test_status_confirmed(self, mock_web3_class, client):
        """Test status endpoint for confirmed transaction"""
        mock_web3 = Mock()
        mock_web3.eth.get_transaction_receipt.return_value = {
            'status': 1,
            'blockNumber': 1000,
            'gasUsed': 21000
        }
        mock_web3.eth.block_number = 1005
        mock_web3_class.return_value = mock_web3

        response = client.get('/status/0xtxhash?chain=base')
        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'confirmed'
        assert data['confirmations'] == 5


class TestAllocateNonce:
    """Test nonce allocation"""

    @patch('main.get_db_connection')
    def test_allocate_fresh_nonce(self, mock_get_conn):
        """Test allocating a fresh nonce"""
        mock_conn = Mock()
        mock_cursor = Mock()
        mock_cursor.fetchone.side_effect = [
            None,  # No stuck transactions
            {'nonce': 0}  # Fresh nonce
        ]
        # Set up context manager for cursor
        mock_cursor_ctx = MagicMock()
        mock_cursor_ctx.__enter__.return_value = mock_cursor
        mock_cursor_ctx.__exit__.return_value = False
        mock_conn.cursor.return_value = mock_cursor_ctx
        mock_get_conn.return_value = mock_conn

        nonce = main.allocate_nonce(8453, '0xFrom')
        assert nonce == 0

    @patch('main.get_db_connection')
    def test_allocate_stuck_nonce_reuse(self, mock_get_conn):
        """Test reusing stuck nonce"""
        mock_conn = Mock()
        mock_cursor = Mock()
        mock_cursor.fetchone.return_value = {
            'nonce': 5,
            'tx_hash': '0xstuck'
        }
        # Set up context manager for cursor
        mock_cursor_ctx = MagicMock()
        mock_cursor_ctx.__enter__.return_value = mock_cursor
        mock_cursor_ctx.__exit__.return_value = False
        mock_conn.cursor.return_value = mock_cursor_ctx
        mock_get_conn.return_value = mock_conn

        nonce = main.allocate_nonce(8453, '0xFrom')
        assert nonce == 5


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
